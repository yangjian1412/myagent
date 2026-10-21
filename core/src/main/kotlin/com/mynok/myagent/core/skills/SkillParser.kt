package com.mynok.myagent.core.skills

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException

/**
 * 解析后的技能。
 *
 * 兼容 Agent Skills 规范与 ClawHub：
 * 技能是一个目录，里面必须有 SKILL.md = YAML frontmatter + Markdown 正文。
 */
data class SkillDefinition(
    /** 技能名（frontmatter.name，缺失时用目录名）。 */
    val name: String,
    /** 何时该用这个技能。 */
    val description: String,
    /** 正文（Markdown 指令）。 */
    val body: String,
    /** 技能目录名。 */
    val slug: String = name,
    val license: String? = null,
    val compatibility: String? = null,
    /** frontmatter 里的 allowed-tools，空格分隔。 */
    val allowedTools: List<String> = emptyList(),
    /** 版本号（来自 metadata.version）。 */
    val version: String? = null,
    val author: String? = null,
    val emoji: String? = null,
    val homepage: String? = null,
    /** 声明的平台限制，如 macos。 */
    val requiredOs: List<String> = emptyList(),
    /** 依赖的环境变量名。 */
    val requiredEnv: List<String> = emptyList(),
    /** 依赖的命令行程序（手机上通常不适用）。 */
    val requiredBins: List<String> = emptyList(),
    /** 依赖的配置文件。 */
    val requiredConfig: List<String> = emptyList(),
    /** 主密钥对应的环境变量名。 */
    val primaryEnv: String? = null,
    /** 解析过程中的问题（比如 YAML 语法错误），不致命。 */
    val warnings: List<String> = emptyList(),
) {
    /** 当前平台是否满足 os 声明。 */
    fun isOsCompatible(currentOs: String = androidOs()): Boolean {
        if (requiredOs.isEmpty()) return true
        return requiredOs.any { it.equals(currentOs, ignoreCase = true) || it.equals("any", true) }
    }

    companion object {
        /** 供测试注入的平台名。 */
        internal var osOverride: String? = null

        internal fun androidOs(): String = osOverride ?: "android"
    }
}

/**
 * SKILL.md 解析器。
 *
 * 放在 core 里是为了能脱离 Android 直接单测：解析是纯文本处理。
 */
object SkillParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(strictMode = false),
    )

    /** frontmatter 的分隔符。 */
    private const val FENCE = "---"

    /**
     * 解析 SKILL.md 文本。
     *
     * @param content SKILL.md 全文
     * @param fallbackName frontmatter 没有 name 时用的名字（一般是目录名）
     */
    fun parse(content: String, fallbackName: String = ""): SkillDefinition? {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()

        // frontmatter 必须是文件开头的 --- ... --- 区块
        val firstContentLine = lines.indexOfFirst { it.isNotBlank() }
        val hasFence = firstContentLine >= 0 && lines[firstContentLine].trim() == FENCE

        val frontmatterText: String
        val body: String

        if (hasFence) {
            val closingIndex = (firstContentLine + 1 until lines.size)
                .firstOrNull { lines[it].trim() == FENCE }
            if (closingIndex == null) {
                // 没有闭合的 frontmatter，剩下的都当正文
                frontmatterText = ""
                body = lines.drop(firstContentLine + 1).joinToString("\n").trim()
            } else {
                frontmatterText = lines
                    .subList(firstContentLine + 1, closingIndex)
                    .joinToString("\n")
                body = lines.drop(closingIndex + 1).joinToString("\n").trim()
            }
        } else {
            frontmatterText = ""
            body = normalized.trim()
        }

        val warnings = mutableListOf<String>()
        val frontmatter = if (frontmatterText.isBlank()) {
            null
        } else {
            try {
                yaml.decodeFromString(SkillFrontmatter.serializer(), frontmatterText)
            } catch (e: YamlException) {
                warnings += "YAML frontmatter 解析失败：${e.message?.lines()?.firstOrNull() ?: "格式错误"}"
                null
            } catch (e: Exception) {
                warnings += "frontmatter 解析失败：${e.message ?: e.javaClass.simpleName}"
                null
            }
        }

        val name = frontmatter?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackName.trim().takeIf { it.isNotEmpty() }
            ?: return null
        val description = frontmatter?.description?.trim().orEmpty()

        val openclaw = frontmatter?.metadata?.openclaw
        val requirements = openclaw?.requires

        return SkillDefinition(
            name = name,
            description = description,
            body = body,
            slug = fallbackName.ifBlank { name },
            license = frontmatter?.license?.trim()?.takeIf { it.isNotEmpty() },
            compatibility = frontmatter?.compatibility?.trim()?.takeIf { it.isNotEmpty() },
            allowedTools = frontmatter?.allowedTools
                ?.split(' ', '\t', '\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            version = frontmatter?.version?.trim()?.takeIf { it.isNotEmpty() }
                ?: frontmatter?.metadata?.version?.trim()?.takeIf { it.isNotEmpty() },
            author = frontmatter?.metadata?.author?.trim()?.takeIf { it.isNotEmpty() },
            emoji = openclaw?.emoji?.trim()?.takeIf { it.isNotEmpty() },
            homepage = openclaw?.homepage?.trim()?.takeIf { it.isNotEmpty() },
            requiredOs = openclaw?.os.orEmpty(),
            requiredEnv = buildList {
                addAll(requirements?.env.orEmpty())
                openclaw?.envVars
                    ?.filter { it.required == true }
                    ?.mapNotNull { it.name }
                    ?.let { addAll(it) }
            }.distinct(),
            requiredBins = buildList {
                addAll(requirements?.bins.orEmpty())
                addAll(requirements?.anyBins.orEmpty())
            }.distinct(),
            requiredConfig = requirements?.config.orEmpty(),
            primaryEnv = openclaw?.primaryEnv?.trim()?.takeIf { it.isNotEmpty() },
            warnings = warnings,
        )
    }
}
