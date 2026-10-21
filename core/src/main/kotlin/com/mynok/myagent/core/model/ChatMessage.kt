package com.mynok.myagent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 消息角色。 */
@Serializable
enum class Role {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}

/**
 * 消息内容片段。
 *
 * 图片 / 文件统一用 Base64 字符串保存：既方便直接落到数据库（不额外做二进制分表），
 * 也正是各 API 协议（OpenAI `image_url` / Anthropic `image` / Gemini `inlineData`）需要的形式。
 */
@Serializable
sealed interface ContentPart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    data class Image(
        val mimeType: String,
        val base64: String,
        val displayName: String? = null,
    ) : ContentPart

    @Serializable
    @SerialName("file")
    data class File(
        val name: String,
        val mimeType: String,
        val base64: String,
    ) : ContentPart
}

/** 纯文本内容。 */
val ContentPart.textOrEmpty: String
    get() = when (this) {
        is ContentPart.Text -> text
        else -> ""
    }

/** 消息中模型的工具调用请求。 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串（各家协议都是字符串形式的 arguments）。 */
    val arguments: String = "{}",
) {
    val isComplete: Boolean get() = arguments.isNotBlank() && arguments.trim().startsWith("{") && arguments.trim().endsWith("}")
}

/** 一条消息。 */
@Serializable
data class ChatMessage(
    val role: Role,
    val parts: List<ContentPart> = emptyList(),
    /** 工具调用名（role=TOOL 时表示是哪个工具的结果；多模态里也可用于标注说话人）。 */
    val name: String? = null,
    /** role=TOOL 时，对应被调用的 tool_call id。 */
    val toolCallId: String? = null,
    /** role=ASSISTANT 时，模型发起的工具调用。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 产生这条消息的 provider 类型，便于回看时知道当时用的是哪套协议。 */
    val provider: String? = null,
    val modelId: String? = null,
    /** 模型的思考内容（reasoning / thinking）。只在用户开启展示时出现在 UI 上。 */
    val reasoning: String = "",
) {
    val text: String
        get() = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

    val images: List<ContentPart.Image>
        get() = parts.filterIsInstance<ContentPart.Image>()

    /** 用户发过来的文件附件。 */
    val files: List<ContentPart.File>
        get() = parts.filterIsInstance<ContentPart.File>()

    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()

    companion object {
        fun user(text: String) = ChatMessage(Role.USER, listOf(ContentPart.Text(text)))
        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, listOf(ContentPart.Text(text)))
        fun system(text: String) = ChatMessage(Role.SYSTEM, listOf(ContentPart.Text(text)))
    }
}

/** 结束原因。 */
@Serializable
enum class FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN,
}
