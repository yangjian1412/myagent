package com.mynok.myagent.core.skills

/**
 * 技能目录（渐进式披露的第一层）。
 *
 * 系统提示里只放「名字 + 一句话说明 + 怎么加载」，
 * 正文要等模型自己决定用这个技能时再通过 skill_read 工具读进来。
 *
 * 预算不够时按「先保名字，再保说明」的顺序裁剪，
 * 保证模型至少知道有哪些技能存在。
 */
object SkillCatalog {

    /** 目录块的字符预算（含标签）。 */
    const val DEFAULT_MAX_CHARS = 4000

    /**
     * 渲染技能目录。
     *
     * @param skills 可用技能
     * @param maxChars 字符预算
     * @param readToolName 加载正文的工具名
     */
    fun render(
        skills: List<SkillDefinition>,
        maxChars: Int = DEFAULT_MAX_CHARS,
        readToolName: String = "skill_read",
    ): String? {
        val eligible = skills.filter { it.description.isNotBlank() }
        if (eligible.isEmpty()) return null

        val header = buildString {
            appendLine("<available_skills>")
            appendLine("以下是已安装的技能。每个技能都是一份操作说明，用到时才读取正文。")
            appendLine("需要某个技能时，调用 $readToolName 传入它的名字，读取完整说明后按说明执行。")
        }

        // 逐个加，预算不够就停；名字优先保留
        val full = StringBuilder(header)
        val trimmed = StringBuilder(header)
        var added = 0
        var usedNamesOnly = false

        for (skill in eligible) {
            val line = formatLine(skill, readToolName)
            val nameOnly = formatNameOnly(skill)
            if (full.length + line.length + "</available_skills>".length <= maxChars) {
                full.appendLine(line)
                trimmed.appendLine(line)
                added++
            } else if (!usedNamesOnly && trimmed.length + nameOnly.length + "</available_skills>".length <= maxChars) {
                // 说明放不下就只留名字
                trimmed.appendLine(nameOnly)
                usedNamesOnly = true
            } else {
                break
            }
        }

        val body = if (added == eligible.size) full.toString() else trimmed.toString()
        if (added == 0 && usedNamesOnly.not()) return null
        return body.trimEnd() + "\n</available_skills>"
    }

    private fun formatLine(skill: SkillDefinition, readToolName: String): String {
        val emoji = skill.emoji?.let { "$it " }.orEmpty()
        val extra = buildList {
            if (skill.allowedTools.isNotEmpty()) {
                add("可用工具：${skill.allowedTools.joinToString(", ")}")
            }
            if (skill.requiredEnv.isNotEmpty()) {
                add("需要密钥：${skill.requiredEnv.joinToString(", ")}")
            }
        }
        val suffix = if (extra.isEmpty()) "" else "（${extra.joinToString("；")}）"
        return "  <skill name=\"${escape(skill.name)}\">${escape(skill.description)}$suffix</skill>"
    }

    private fun formatNameOnly(skill: SkillDefinition): String =
        "  <skill name=\"${escape(skill.name)}\"/>"

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", " ")
        .trim()
}
