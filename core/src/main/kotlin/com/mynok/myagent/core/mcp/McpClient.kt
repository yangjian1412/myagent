package com.mynok.myagent.core.mcp

import com.mynok.myagent.core.model.SseParser
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 客户端（Streamable HTTP 传输）。
 *
 * 传输：向 [McpServerConfig.url] POST 一条 JSON-RPC 2.0 消息。响应有两种形态，
 * 都支持：
 * - `Content-Type: application/json`：整个 body 是一条 JSON-RPC 响应；
 * - `Content-Type: text/event-stream`：SSE，每个 `data:` 是一条 JSON-RPC 消息。
 *
 * 这里只做客户端，不做服务端。
 */
class McpClient(
    private val config: McpServerConfig,
    private val http: Http,
    /** 单个请求的超时时间（秒）。 */
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /** 请求 id 自增，保证同一个 session 内不重复。 */
    private val requestCounter = AtomicLong(0)

    /** server 在 initialize 时下发的会话 id，后续请求要带上。 */
    @Volatile
    private var sessionId: String? = null

    /** 协商后的协议版本。 */
    @Volatile
    var protocolVersion: String? = null
        private set

    @Volatile
    private var serverName: String? = null

    @Volatile
    private var serverVersion: String? = null

    @Volatile
    private var toolCount: Int = 0

    @Volatile
    private var lastError: String? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /** 配置本身（给 [McpTool] 拼工具名用）。 */
    val serverConfig: McpServerConfig get() = config

    /** 当前状态快照。 */
    fun info(): McpServerInfo = McpServerInfo(
        config = config,
        protocolVersion = protocolVersion,
        serverName = serverName,
        serverVersion = serverVersion,
        toolCount = toolCount,
        lastError = lastError,
    )

    /**
     * 握手：initialize + notifications/initialized。
     *
     * 协议版本以 server 返回的为准；server 没返回就用我们请求的版本。
     */
    suspend fun connect(): McpServerInfo {
        val params = buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", MCP_CLIENT_NAME)
                put("version", MCP_CLIENT_VERSION)
            })
        }

        val id = requestCounter.incrementAndGet()
        val response = post(JsonRpcCodec.encodeRequest(id, METHOD_INITIALIZE, params))
        val message = extractMessage(response, id)
        message.error?.let { throw toException(it, METHOD_INITIALIZE) }

        val init = decode<McpInitializeResult>(message.result)
        protocolVersion = init.protocolVersion?.takeIf { it.isNotBlank() } ?: MCP_PROTOCOL_VERSION
        serverName = init.serverInfo?.name
        serverVersion = init.serverInfo?.version
        lastError = null
        isConnected = true

        // 通知 server 握手完成；server 端的错误不影响我们继续用。
        notify(METHOD_INITIALIZED)

        return info()
    }

    /** 列出 server 上的全部工具（自动翻页）。 */
    suspend fun listTools(): List<McpToolInfo> {
        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        var page = 0
        do {
            val params = cursor?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("cursor", it) } }
            val result = call(METHOD_TOOLS_LIST, params)
            val list = decode<McpListToolsResult>(result)
            tools += list.tools
            cursor = list.nextCursor?.takeIf { it.isNotBlank() }
            page++
        } while (cursor != null && page < MAX_TOOL_PAGES)

        toolCount = tools.size
        return tools
    }

    /** 调用 server 上的一个工具。 */
    suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = call(METHOD_TOOLS_CALL, params)
        return decode<McpToolCallResult>(result).toToolResult()
    }

    /**
     * 断开连接。
     *
     * Streamable HTTP 本身没有断连动作，这里只清理本地状态（会话 id 由 server 管理）。
     */
    suspend fun disconnect() {
        isConnected = false
        sessionId = null
        protocolVersion = null
        serverName = null
        serverVersion = null
        toolCount = 0
    }

    // ------------------------------------------------------------------ 内部实现

    /** 发一条 JSON-RPC 请求并返回 result。 */
    private suspend fun call(method: String, params: JsonObject? = null): JsonObject {
        val id = requestCounter.incrementAndGet()
        val response = post(JsonRpcCodec.encodeRequest(id, method, params))
        val message = extractMessage(response, id)
        message.error?.let { throw toException(it, method) }
        val result = message.result
        if (result !is JsonObject) {
            throw fail(
                "MCP 方法 $method 的响应里没有 result 对象",
                code = McpException.ERROR_PARSE,
                rawBody = result.toString().take(500),
            )
        }
        return result
    }

    /** 发一条通知（没有 id，没有返回值）。 */
    private suspend fun notify(method: String, params: JsonObject? = null) {
        try {
            post(JsonRpcCodec.encodeNotification(method, params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 通知失败不阻断流程，忽略即可。
        }
    }

    /** POST 一条消息，返回原始响应。 */
    private suspend fun post(body: String): HttpResponse {
        val request = HttpRequest(
            url = config.url,
            method = "POST",
            headers = buildHeaders(),
            body = body,
            contentType = "application/json; charset=utf-8",
            timeoutSeconds = timeoutSeconds,
        )

        val response = try {
            withTimeout(timeoutSeconds * 1000) { http.execute(request) }
        } catch (e: TimeoutCancellationException) {
            throw fail("MCP 请求超时（${timeoutSeconds}s）：${config.name}", cause = e)
        } catch (e: McpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw fail("MCP 请求失败：${e.message ?: e::class.simpleName}", cause = e)
        }

        // server 可能在 initialize 时下发会话 id，后续请求要带回去。
        response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

        if (!response.isSuccessful) throw httpError(response)
        return response
    }

    /** 记一次错误并生成异常。 */
    private fun fail(
        message: String,
        code: Int = McpException.ERROR_TRANSPORT,
        statusCode: Int? = null,
        rawBody: String? = null,
        cause: Throwable? = null,
    ): McpException {
        lastError = message
        return McpException(code, message, statusCode, rawBody, cause)
    }

    /** 请求头：固定头 + 鉴权 + 用户自定义头。 */
    private fun buildHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json, text/event-stream",
        )
        config.bearerToken?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        // 自定义头优先级最高，方便用户覆盖默认行为。
        headers += config.headers
        sessionId?.let { headers["Mcp-Session-Id"] = it }
        // 协议版本协商结果要在后续请求里带上（initialize 之前没有这个值）。
        protocolVersion?.let { headers["MCP-Protocol-Version"] = it }
        return headers
    }

    /**
     * 从响应里取出目标消息。
     *
     * 响应可能是 JSON（整个 body 一条消息），也可能是 SSE（每条 data 一条消息）。
     */
    private fun extractMessage(response: HttpResponse, id: Long): JsonRpcMessage {
        val messages = parseMessages(response)
        val message = messages.firstOrNull { it.idText == id.toString() }
            ?: messages.firstOrNull { it.isResponse }
            ?: throw fail(
                "MCP 响应里找不到 id=$id 的结果",
                code = McpException.ERROR_PARSE,
                rawBody = response.body.take(500),
            )
        return message
    }

    /** 解析响应体里的所有 JSON-RPC 消息。 */
    private fun parseMessages(response: HttpResponse): List<JsonRpcMessage> {
        val body = response.body
        val contentType = response.header("Content-Type").orEmpty()
        val isSse = contentType.contains("text/event-stream", ignoreCase = true) || looksLikeSse(body)
        if (!isSse) {
            return listOfNotNull(JsonRpcCodec.decodeMessage(body))
        }
        // SSE：复用 core 里的解析器，每条 data 都是一条 JSON-RPC 消息。
        val parser = SseParser()
        val messages = mutableListOf<JsonRpcMessage>()
        for (event in parser.onChunk(body) + parser.finish()) {
            JsonRpcCodec.decodeMessage(event.data)?.let(messages::add)
        }
        return messages
    }

    /** body 看起来像 SSE（有些 server 不带 Content-Type）。 */
    private fun looksLikeSse(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("event:") ||
            trimmed.startsWith("data:") ||
            trimmed.startsWith(":")
    }

    /** HTTP 非 2xx：优先用 body 里的 JSON-RPC error。 */
    private fun httpError(response: HttpResponse): McpException {
        val body = response.body
        val rpcError = JsonRpcCodec.decodeMessage(body)?.error
        return if (rpcError != null) {
            fail(
                rpcError.message.ifBlank { "MCP 服务返回 HTTP ${response.code}" },
                code = rpcError.code,
                statusCode = response.code,
                rawBody = body.take(500),
            )
        } else {
            fail(
                "MCP 服务返回 HTTP ${response.code}：${body.take(200)}",
                statusCode = response.code,
                rawBody = body.take(500),
            )
        }
    }

    private fun toException(error: JsonRpcError, method: String): McpException = fail(
        error.message.ifBlank { "MCP 方法 $method 失败（code=${error.code}）" },
        code = error.code,
    )

    /** 把 result 解成具体的数据类。 */
    private inline fun <reified T> decode(result: kotlinx.serialization.json.JsonElement): T {
        if (result !is JsonObject) {
            throw fail("MCP 响应的 result 不是对象", code = McpException.ERROR_PARSE)
        }
        return runCatching { MyAgentJson.decodeFromString<T>(result.toString()) }
            .getOrElse {
                throw fail(
                    "MCP 响应结构不符合预期：${it.message ?: it::class.simpleName}",
                    code = McpException.ERROR_PARSE,
                    rawBody = result.toString().take(500),
                )
            }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 30

        /** tools/list 最多翻多少页，防止 server 一直给游标。 */
        const val MAX_TOOL_PAGES: Int = 20

        const val METHOD_INITIALIZE: String = "initialize"
        const val METHOD_INITIALIZED: String = "notifications/initialized"
        const val METHOD_TOOLS_LIST: String = "tools/list"
        const val METHOD_TOOLS_CALL: String = "tools/call"
    }
}
