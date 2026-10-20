package com.mynok.myagent.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 流式事件。三个协议适配器把各自的 SSE 事件统一收敛到这一层，
 * 上层的 agent 循环因此完全不感知协议差异。
 */
sealed interface ModelEvent {

    /** 文本增量。 */
    data class TextDelta(val text: String) : ModelEvent

    /** 思考过程增量（Anthropic thinking / OpenAI reasoning）。 */
    data class ReasoningDelta(val text: String) : ModelEvent

    /**
     * 工具调用增量。
     * @param index 同一次回复里的第几个工具调用（用于把分片拼回去）
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : ModelEvent

    /** 用量统计。 */
    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
    ) : ModelEvent

    /** 结束。 */
    data class Completed(val finishReason: FinishReason = FinishReason.STOP) : ModelEvent

    /** 失败。 */
    data class Failed(val error: Throwable) : ModelEvent
}

/** 非流式调用返回的结果。 */
data class ModelResult(
    val message: ChatMessage,
    val usage: ModelEvent.Usage? = null,
    val finishReason: FinishReason = FinishReason.STOP,
)

/**
 * 模型适配器接口。
 *
 * 实现类必须保证：[stream] 发射的事件序列最终一定以 [ModelEvent.Completed] 或
 * [ModelEvent.Failed] 收尾，否则 agent 循环会挂住。
 */
interface ModelProvider {

    val profile: ModelProfile

    /** 流式生成。 */
    suspend fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent>

    /** 非流式生成（内部复用流式实现，只是收集起来）。 */
    suspend fun complete(request: ModelRequest): ModelResult
}

/** 协议错误，带上可读信息以便直接展示给用户。 */
class ModelException(
    message: String,
    val statusCode: Int? = null,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 便于测试与调试：把任意元素转成可读 JSON。 */
fun JsonElement?.orEmptyObject(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())
