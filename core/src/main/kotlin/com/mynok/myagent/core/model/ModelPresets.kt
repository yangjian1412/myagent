package com.mynok.myagent.core.model

/**
 * 某类模型的已知能力。
 *
 * 服务端大多不返回这些信息（OpenAI / Anthropic 的 `/models` 只有 id），
 * 所以按模型名做一份"尽力而为"的预设；用户永远可以在界面上改。
 */
data class ModelPreset(
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val reasoningEfforts: List<ReasoningEffort> = emptyList(),
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
)

/**
 * 按模型 id 猜能力。
 *
 * 只做前缀/包含匹配，宁可不命中也不要猜错；命中后界面会填好值但允许修改。
 */
object ModelPresets {

    private val rules: List<Pair<(String) -> Boolean, ModelPreset>> = listOf(
        // OpenAI
        match("gpt-5", "gpt5") to ModelPreset(
            contextWindow = 400_000,
            maxOutputTokens = 128_000,
            reasoningEfforts = listOf(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ),
            supportsVision = true,
            supportsTools = true,
        ),
        prefix("o3", "o4") to ModelPreset(
            contextWindow = 200_000,
            maxOutputTokens = 100_000,
            reasoningEfforts = listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ),
            supportsVision = true,
            supportsTools = true,
        ),
        match("gpt-4.1", "gpt-4o") to ModelPreset(
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsVision = true,
            supportsTools = true,
        ),
        // Anthropic：没有 effort 档位，是 token 预算
        match("claude") to ModelPreset(
            contextWindow = 200_000,
            maxOutputTokens = 32_000,
            reasoningEfforts = listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ),
            supportsVision = true,
            supportsTools = true,
        ),
        // Gemini：2.5 Pro 不能关闭思考，2.5 Flash 可以
        match("gemini-2.5-pro") to ModelPreset(
            contextWindow = 1_048_576,
            maxOutputTokens = 65_536,
            reasoningEfforts = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            supportsVision = true,
            supportsTools = true,
        ),
        match("gemini-2.5-flash", "gemini-2.0") to ModelPreset(
            contextWindow = 1_048_576,
            maxOutputTokens = 65_536,
            reasoningEfforts = listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ),
            supportsVision = true,
            supportsTools = true,
        ),
        match("gemini") to ModelPreset(
            contextWindow = 1_048_576,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsTools = true,
        ),
        // 始终思考、不接受 effort 参数的模型
        match("deepseek-r1", "deepseek-reasoner", "glm-z1", "qwq") to ModelPreset(
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsTools = false,
        ),
        match("deepseek", "qwen", "kimi", "glm", "moonshot") to ModelPreset(
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsTools = true,
        ),
        // 本地模型
        match("strata", "local-model") to ModelPreset(
            contextWindow = 32_768,
            maxOutputTokens = 4_096,
            supportsTools = true,
        ),
    )

    fun lookup(modelId: String): ModelPreset? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        return rules.firstOrNull { (predicate, _) -> predicate(id) }?.second
    }

    private fun match(vararg needles: String): (String) -> Boolean = { id ->
        needles.any { id.startsWith(it) || id.contains(it) }
    }

    private fun prefix(vararg needles: String): (String) -> Boolean = { id ->
        needles.any { id.startsWith(it) }
    }
}
