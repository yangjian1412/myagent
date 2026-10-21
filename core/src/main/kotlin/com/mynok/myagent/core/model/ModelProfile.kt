package com.mynok.myagent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 协议类型。三种原生协议各有一个适配器。 */
@Serializable
enum class ProviderType {
    /** OpenAI 兼容：OpenAI / DeepSeek / Kimi / Qwen / OpenRouter / Ollama / llama.cpp / vLLM / strata ... */
    @SerialName("openai_compat") OPENAI_COMPAT,

    /** Anthropic Messages API 原生。 */
    @SerialName("anthropic") ANTHROPIC,

    /** Google Gemini 原生。 */
    @SerialName("gemini") GEMINI,
}

/**
 * 一个模型档案（= 配置里的一条模型）。
 *
 * 只存 [secretId] 而不是明文 key；真正的 key 由 app 层的加密存储按 id 取。
 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String,
    val providerType: ProviderType = ProviderType.OPENAI_COMPAT,
    val baseUrl: String,
    val modelId: String,
    /** 指向加密存储中的密钥 id；null 表示该服务不需要鉴权（本地模型常见）。 */
    val secretId: String? = null,
    /** 额外请求头，例如某些中转服务需要 `HTTP-Referer` / `X-Title`。 */
    val headers: Map<String, String> = emptyMap(),
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    /**
     * 是否支持把文件原样发给模型（PDF 等）。
     *
     * 各家协议差异很大：Gemini 用 `inlineData`、Anthropic 用 `document` 块、
     * OpenAI 兼容用 `file` 内容块，而本地推理服务（Ollama、LM Studio）多半不支持。
     * 声明为 false 时，App 会在发送前拦下来并提示换模型，
     * 而不是发一个会被服务端 400 拒掉的请求。
     */
    val supportsFileInput: Boolean = false,
    val supportsStreaming: Boolean = true,
    /** 上下文窗口（token），用于压缩策略。 */
    val contextWindow: Int = 32_768,
    /** 单次输出上限。 */
    val maxOutputTokens: Int = 4_096,
    val temperature: Double? = null,
    val topP: Double? = null,
    /**
     * 该模型支持的思考强度等级。
     *
     * 各家模型并不一致（有的只有 low/high，有的支持 minimal），
     * 所以由模型档案自己声明，对话页只展示这里列出的等级。
     * 空表示不支持扩展思考。
     */
    val reasoningEfforts: List<ReasoningEffort> = emptyList(),
    /** 透传到请求体底部的额外字段。 */
    val extraBody: JsonObject = JsonObject(emptyMap()),
    /** 是否为内置档案（内置的不可删除，只可改副本）。 */
    val builtin: Boolean = false,
) {
    /** 是否为局域网/明文连接，UI 需要给出提示。 */
    val isCleartext: Boolean
        get() = baseUrl.startsWith("http://")

    /** 本地服务（127.0.0.1 / 局域网 IP / .local）通常不支持原生 tools 协议。 */
    val looksLocal: Boolean
        get() {
            val host = baseUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
            return host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
                host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.") ||
                host.startsWith("172.")
        }
}

/** 一次模型请求。 */
data class ModelRequest(
    val messages: List<ChatMessage>,
    /** 顶层系统提示（OpenAI 兼容会作为第一条 system 消息，Anthropic 是顶层字段，Gemini 是 systemInstruction）。 */
    val system: String? = null,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = true,
    /** 思考强度。OFF 表示不加扩展思考参数。 */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    /** 透传到底部的额外字段。 */
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** 工具声明（OpenAI function calling 风格）。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 描述参数。 */
    val parameters: JsonObject,
) {
    val requiresApproval: Boolean get() = false
}
