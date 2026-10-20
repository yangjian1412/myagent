package com.mynok.myagent.core.mcp

import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP（Model Context Protocol）协议层：数据类 + JSON-RPC 2.0 编解码。
 *
 * 这里只做客户端需要的那部分：配置、握手、工具清单、工具调用结果。
 * core 是纯 JVM 模块，因此不碰任何 Android API，也不做 socket/stdio 传输。
 */

/** 本客户端请求的 MCP 协议版本。server 返回别的版本时以 server 的为准。 */
const val MCP_PROTOCOL_VERSION: String = "2025-06-18"

/** 客户端标识，initialize 时上报给 server。 */
const val MCP_CLIENT_NAME: String = "MyAgent"
const val MCP_CLIENT_VERSION: String = "0.1.0"

/**
 * 一个 MCP server 的配置。
 *
 * [bearerToken] 是明文 token，由 app 层从加密存储取出后再传进来，core 不负责持久化。
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** `Authorization: Bearer <token>`。 */
    val bearerToken: String? = null,
    /** 额外请求头（例如某些网关需要 `X-Api-Key`）。 */
    val headers: Map<String, String> = emptyMap(),
    /** 工具名前缀，默认用 server 名的 slug。 */
    val toolPrefix: String? = null,
) {
    /** 工具名前缀：显式配置优先，否则用 server 名的 slug。 */
    val prefix: String
        get() = toolPrefix?.takeIf { it.isNotBlank() }?.let { McpToolFactory.slug(it) }
            ?: McpToolFactory.slug(name.ifBlank { id })
}

/** 一个 MCP server 的运行时状态。 */
@Serializable
data class McpServerInfo(
    val config: McpServerConfig,
    /** 协商后的协议版本（server 返回什么就是什么）。 */
    val protocolVersion: String? = null,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val toolCount: Int = 0,
    val lastError: String? = null,
)

/** server 侧的一个工具。 */
@Serializable
data class McpToolInfo(
    val name: String,
    val description: String? = null,
    /** JSON Schema；缺失时用空对象 schema 兜底。 */
    val inputSchema: JsonObject? = null,
) {
    /** 真正给模型看的参数 schema。 */
    val effectiveSchema: JsonObject
        get() = inputSchema ?: EMPTY_INPUT_SCHEMA
}

/** server 的实现信息（initialize 的 serverInfo）。 */
@Serializable
data class McpImplementation(
    val name: String? = null,
    val version: String? = null,
)

/** initialize 的返回。 */
@Serializable
data class McpInitializeResult(
    val protocolVersion: String? = null,
    val serverInfo: McpImplementation? = null,
    val capabilities: JsonObject? = null,
    /** server 给模型的额外提示词。 */
    val instructions: String? = null,
)

/** tools/list 的一页返回。 */
@Serializable
data class McpListToolsResult(
    val tools: List<McpToolInfo> = emptyList(),
    /** 分页游标，非空说明还有下一页。 */
    val nextCursor: String? = null,
)

/** 工具返回的一个内容块。 */
@Serializable
data class McpContentBlock(
    /** text / image / audio / resource。 */
    val type: String = "text",
    val text: String? = null,
    /** image、audio 的 base64 数据。 */
    val data: String? = null,
    val mimeType: String? = null,
    val uri: String? = null,
    val name: String? = null,
    /** type=resource 时的内层资源对象（{uri, mimeType, text|blob}）。 */
    val resource: JsonObject? = null,
)

/** tools/call 的返回。 */
@Serializable
data class McpToolCallResult(
    val content: List<McpContentBlock> = emptyList(),
    val isError: Boolean = false,
    /** server 额外返回的结构化结果。 */
    val structuredContent: JsonElement? = null,
)

/** JSON-RPC 的错误对象。 */
@Serializable
data class JsonRpcError(
    val code: Int = 0,
    val message: String = "",
    val data: JsonElement? = null,
)

/**
 * 一条 JSON-RPC 2.0 消息（请求 / 响应 / 通知都用它表示）。
 *
 * [id] 用 [JsonElement] 存，因为 server 可能回数字也可能回字符串。
 */
@Serializable
data class JsonRpcMessage(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    /** 非空表示这是一条请求或通知。 */
    val method: String? = null,
    val params: JsonObject? = null,
    /** 响应内容（缺失时是 [JsonNull]）。 */
    val result: JsonElement = JsonNull,
    val error: JsonRpcError? = null,
) {
    /** id 归一化成字符串，方便和请求 id 比较。 */
    val idText: String? get() = (id as? JsonPrimitive)?.content

    /** 没有 method 就是响应。 */
    val isResponse: Boolean get() = method == null
}

/** JSON-RPC 2.0 编解码。 */
object JsonRpcCodec {

    const val VERSION: String = "2.0"

    /** 编码一条带 id 的请求。 */
    fun encodeRequest(id: Long, method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", JsonPrimitive(id))
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** 编码一条通知（没有 id）。 */
    fun encodeNotification(method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** 解析一条消息；不是合法 JSON-RPC 消息时返回 null。 */
    fun decodeMessage(text: String): JsonRpcMessage? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { MyAgentJson.decodeFromString<JsonRpcMessage>(trimmed) }.getOrNull()
    }
}

/** MCP 客户端异常。JSON-RPC 错误码放在 [code] 里，HTTP 状态码放在 [statusCode] 里。 */
class McpException(
    val code: Int,
    message: String,
    val statusCode: Int? = null,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    constructor(message: String) : this(ERROR_INTERNAL, message)

    constructor(message: String, cause: Throwable) : this(ERROR_INTERNAL, message, cause = cause)

    companion object {
        const val ERROR_PARSE: Int = -32700
        const val ERROR_INVALID_REQUEST: Int = -32600
        const val ERROR_METHOD_NOT_FOUND: Int = -32601
        const val ERROR_INVALID_PARAMS: Int = -32602
        const val ERROR_INTERNAL: Int = -32603

        /** 传输层错误（HTTP 非 2xx、网络异常、超时）。 */
        const val ERROR_TRANSPORT: Int = -32000
    }
}

/**
 * 把 MCP 的工具返回转成我们的 [ToolResult]。
 *
 * 多个 text 块用换行连接；image 块转成 [ContentPart.Image]；server 标记 isError 时保留错误标记。
 */
fun McpToolCallResult.toToolResult(): ToolResult {
    val texts = mutableListOf<String>()
    val images = mutableListOf<ContentPart.Image>()

    for (block in content) {
        when (block.type.lowercase()) {
            "text" -> block.text?.let { texts += it }

            "image" -> {
                val data = block.data
                if (!data.isNullOrBlank()) {
                    images += ContentPart.Image(
                        mimeType = block.mimeType?.takeIf { it.isNotBlank() } ?: "image/png",
                        base64 = data,
                    )
                }
            }

            "resource" -> {
                val resource = block.resource
                val text = (resource?.get("text") as? JsonPrimitive)?.content
                if (!text.isNullOrEmpty()) {
                    texts += text
                } else {
                    val uri = (resource?.get("uri") as? JsonPrimitive)?.content ?: block.uri
                    val name = block.name ?: (resource?.get("name") as? JsonPrimitive)?.content
                    texts += buildString {
                        if (!name.isNullOrBlank()) append("[").append(name).append("] ")
                        append(uri ?: "resource")
                    }
                }
            }

            else -> {
                // 未知类型：能取到文本就当文本用
                block.text?.let { texts += it }
            }
        }
    }

    val text = when {
        texts.isNotEmpty() -> texts.joinToString("\n")
        structuredContent != null && structuredContent !is JsonNull -> structuredContent.toString()
        else -> ""
    }
    return ToolResult(content = text, isError = isError, images = images)
}

/** server 没给 inputSchema 时的空 schema。 */
private val EMPTY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
}
