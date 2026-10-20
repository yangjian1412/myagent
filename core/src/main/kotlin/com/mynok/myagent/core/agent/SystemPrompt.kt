package com.mynok.myagent.core.agent

/**
 * 系统提示拼装。
 *
 * 顺序固定：Agent 身份 → 用户长期偏好 → 会话级提示 → 技能提示。
 * 放在 core 里是为了能脱离 Android 直接单测。
 */
object SystemPrompt {

    const val DEFAULT_AGENT_NAME = "MyAgent"

    fun build(
        agentName: String?,
        preferences: String? = null,
        extraPrompts: List<String?> = emptyList(),
    ): String? {
        val name = agentName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AGENT_NAME
        return buildString {
            appendLine("你的名字是「$name」。")
            preferences?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs ->
                appendLine("请遵循用户的长期偏好：")
                appendLine(prefs)
            }
            extraPrompts.forEach { prompt ->
                prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine(it) }
            }
        }.trim().ifBlank { null }
    }
}
