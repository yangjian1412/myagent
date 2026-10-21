package com.mynok.myagent.tools.android

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.stringArg
import com.mynok.myagent.data.repo.SkillRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 读取技能正文。
 *
 * 这是「渐进式披露」的第二层：系统提示里只有技能名和说明，
 * 模型觉得需要时才调这个工具把完整指令读进来。
 */
class SkillReadTool(
    private val skills: SkillRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "skill_read",
        description = "读取某个已安装技能的完整说明（SKILL.md 正文）。" +
            "当任务与某个技能相关时，先读它的说明再照着做。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "技能名，如 morning-brief")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("name")) })
        },
    )

    override val risk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.stringArg("name", "skill", "skill_name")
            ?: return ToolResult.error("缺少 name 参数")
        val entity = skills.get(name.trim())
            ?: return ToolResult.error(
                "没有找到技能「$name」。可用技能见系统提示里的技能目录。",
            )
        if (!entity.enabled) {
            return ToolResult.error("技能「${entity.name}」已被用户停用。")
        }
        val body = entity.prompt.trim()
        if (body.isEmpty()) {
            return ToolResult.error("技能「${entity.name}」没有正文内容。")
        }
        val header = buildString {
            appendLine("# 技能：${entity.name}")
            if (entity.description.isNotBlank()) appendLine(entity.description)
            if (entity.forceTools.isNotBlank()) {
                appendLine("允许使用的工具：${entity.forceTools}")
            }
            appendLine()
        }
        return ToolResult.ok(header + body)
    }
}

/**
 * 写技能文件。
 *
 * 让模型自己沉淀技能：把一段可复用的做法写成 `SKILL.md`。
 */
class SkillWriteTool(
    private val skills: SkillRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "skill_write",
        description = "创建一个新的技能文件（SKILL.md），把可复用的做法沉淀下来。" +
            "技能会被扫描进技能列表，之后可以按名字读取并使用。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "技能名，小写字母、数字和连字符，如 daily-brief")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "一句话说明这个技能做什么、什么时候用")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "技能正文（Markdown），写清楚具体步骤")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "已存在同名技能时是否覆盖，默认 false")
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("description"))
                add(JsonPrimitive("body"))
            })
        },
    )

    override val risk = ToolRisk.WRITE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!skills.isDirectoryBound()) {
            return ToolResult.error(
                "还没有设置技能目录。请让用户在「模型 → 技能」页面选择技能目录后再试。",
            )
        }
        val name = args.stringArg("name")?.trim().orEmpty()
        if (!skills.isValidName(name)) {
            return ToolResult.error(
                "技能名不合法：只能用小写字母、数字和连字符，长度 1-64，且不能以连字符开头结尾。",
            )
        }
        val description = args.stringArg("description")?.trim().orEmpty()
        if (description.isEmpty()) return ToolResult.error("缺少 description 参数")
        val body = args.stringArg("body", "content", "prompt")?.trim().orEmpty()
        if (body.isEmpty()) return ToolResult.error("缺少 body 参数")

        val existing = skills.get(name)
        val overwrite = args["overwrite"]?.let {
            (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        } ?: false
        if (existing != null && !overwrite) {
            return ToolResult.error("技能「$name」已存在。如果要覆盖，请带 overwrite=true。")
        }

        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: ${description.replace("\n", " ")}")
            appendLine("---")
            appendLine()
            appendLine(body)
        }
        val ok = skills.writeSkillFile(name, content)
        if (!ok) {
            return ToolResult.error("写入技能文件失败，请检查技能目录是否可写。")
        }
        skills.syncFromFiles()
        return ToolResult.ok("已创建技能「$name」。用户可以在「模型 → 技能」里看到并停用它。")
    }
}

/**
 * 列出已安装技能。
 *
 * 系统提示里已经有技能目录了，这个工具主要用于确认某个技能是否存在。
 */
class SkillListTool(
    private val skills: SkillRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "skill_list",
        description = "列出已安装的技能及其说明。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
            put("required", buildJsonArray { })
        },
    )

    override val risk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val all = skills.getEnabled()
        if (all.isEmpty()) {
            return ToolResult.ok("当前没有启用中的技能。")
        }
        val text = all.joinToString("\n") { skill ->
            val emoji = skill.emoji?.let { "$it " }.orEmpty()
            "- ${emoji}${skill.name}：${skill.description.ifBlank { "（无说明）" }}"
        }
        return ToolResult.ok("已启用 ${all.size} 个技能：\n$text")
    }
}
