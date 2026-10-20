package com.mynok.myagent.core.model

/**
 * 思考强度（扩展思考 / reasoning）。
 *
 * 三家协议的表达方式完全不同，这里做一层归一：
 * - OpenAI 兼容：`reasoning_effort` = low / medium / high
 * - Anthropic：`thinking.budget_tokens`
 * - Gemini：`generationConfig.thinkingConfig.thinkingBudget`
 *
 * [OFF] 表示不向请求里加任何思考参数（本地小模型大多不支持，默认就是关）。
 */
enum class ReasoningEffort(val label: String, val wire: String, val budgetTokens: Int) {
    OFF("关闭", "", 0),
    MINIMAL("极低", "minimal", 512),
    LOW("低", "low", 1_024),
    MEDIUM("中", "medium", 4_096),
    HIGH("高", "high", 16_384),
    ;

    val isOn: Boolean get() = this != OFF

    companion object {
        fun from(raw: String?): ReasoningEffort =
            entries.firstOrNull { it.name == raw } ?: OFF

        /** 从逗号分隔的等级列表解析；未知项忽略。 */
        fun parseList(raw: String?): List<ReasoningEffort> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',')
                .mapNotNull { item -> entries.firstOrNull { it.name == item.trim().uppercase() } }
                .distinct()
        }

        fun join(list: List<ReasoningEffort>): String = list.joinToString(",") { it.name }
    }
}
