package com.mynok.myagent.core.mcp

import com.mynok.myagent.core.net.OkHttpHttp
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MCP 客户端测试：用 MockWebServer 模拟 Streamable HTTP 传输的 MCP 服务端。
 */
class McpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ 辅助

    private fun config(url: String = server.url("/mcp").toString()) = McpServerConfig(
        id = "srv-1",
        name = "demo server",
        url = url,
    )

    private fun client(url: String = server.url("/mcp").toString()) = McpClient(config(url), OkHttpHttp())

    /**
     * MCP 客户端把每个请求包在 `withTimeout` 里；runTest 的虚拟时钟会在真实网络 IO
     * 还没返回时就把超时触发掉，所以这里把请求放到真实调度器上执行。
     */
    private suspend fun <T> realIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun captureError(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    private fun jsonRpcResult(id: String?, result: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":${id ?: "null"},"result":$result}""")

    private fun jsonRpcError(id: String?, code: Int, message: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":${id ?: "null"},"error":{"code":$code,"message":"$message"}}""")

    /** 根据 JSON-RPC 的 method 分发响应。 */
    private inner class McpDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // 用 clone 读 body，否则 takeRequest() 拿到的请求体已经被读空了
            val body = request.body.clone().readUtf8()
            val json = MyAgentJson.parseToJsonElement(body).jsonObject
            val method = json["method"]?.jsonPrimitive?.content
            val id = json["id"]?.jsonPrimitive?.content
            return when (method) {
                "initialize" -> jsonRpcResult(
                    id,
                    """{"protocolVersion":"2025-06-18","serverInfo":{"name":"demo","version":"1.2.3"},"capabilities":{"tools":{}}}""",
                )

                "notifications/initialized" -> MockResponse().setResponseCode(202)

                "tools/list" -> jsonRpcResult(
                    id,
                    """{"tools":[{"name":"echo","description":"回声工具","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}""",
                )

                "tools/call" -> jsonRpcResult(
                    id,
                    """{"content":[{"type":"text","text":"工具输出"}],"isError":false}""",
                )

                else -> MockResponse().setResponseCode(404).setBody("unknown method")
            }
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `initialize 成功返回协议版本与 serverInfo`() = runTest {
        server.dispatcher = McpDispatcher()
        val client = client()

        val info = realIo { client.connect() }

        assertEquals("2025-06-18", info.protocolVersion)
        assertEquals("demo", info.serverName)
        assertEquals("1.2.3", info.serverVersion)
        assertTrue("握手后应标记为已连接", client.isConnected)
    }

    @Test
    fun `initialize 之后会发送 notifications initialized 通知`() = runTest {
        server.dispatcher = McpDispatcher()

        realIo { client().connect() }

        val methods = (1..2).map { server.takeRequest().body.readUtf8() }
            .map { MyAgentJson.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content }
        assertTrue("应发送 initialize，实际：$methods", "initialize" in methods)
        assertTrue("应发送 initialized 通知，实际：$methods", "notifications/initialized" in methods)
    }

    @Test
    fun `tools list 返回工具清单`() = runTest {
        server.dispatcher = McpDispatcher()
        val client = client()
        realIo { client.connect() }

        val tools = realIo { client.listTools() }

        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
        assertEquals("回声工具", tools[0].description)
        assertEquals("object", tools[0].effectiveSchema["type"]!!.jsonPrimitive.content)
        assertEquals(1, client.info().toolCount)
    }

    @Test
    fun `tools call 返回文本内容并映射成 ToolResult`() = runTest {
        server.dispatcher = McpDispatcher()
        val client = client()
        realIo { client.connect() }

        val result = realIo { client.callTool("echo", buildJsonObject { put("text", "你好") }) }

        assertFalse(result.isError)
        assertEquals("工具输出", result.content)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `服务端返回 JSON-RPC error 时抛出 McpException`() = runTest {
        server.enqueue(jsonRpcError("1", -32601, "方法不存在"))

        val error = captureError { realIo { client().connect() } }

        assertNotNull("JSON-RPC error 应当抛出 McpException", error)
        assertTrue("实际抛出的是 ${error!!::class.simpleName}", error is McpException)
        assertEquals(-32601, (error as McpException).code)
        assertTrue(
            "异常信息应包含服务端给的描述，实际：${error.message}",
            error.message!!.contains("方法不存在"),
        )
    }

    @Test
    fun `响应是 text event stream 形式时也能正确解析`() = runTest {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" +
            "{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"sse-server\",\"version\":\"9.9\"}}}\n\n"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body),
        )
        // initialize 之后客户端还会发一条 notifications/initialized 通知
        server.enqueue(MockResponse().setResponseCode(202))

        val info = realIo { client().connect() }

        assertEquals("2025-06-18", info.protocolVersion)
        assertEquals("sse-server", info.serverName)
        assertEquals("9.9", info.serverVersion)
    }

    @Test
    fun `HTTP 非 2xx 时抛出 McpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = captureError { realIo { client().connect() } }

        assertNotNull("HTTP 500 应当抛出 McpException", error)
        assertTrue(error is McpException)
        assertEquals(500, (error as McpException).statusCode)
    }

    @Test
    fun `HTTP 404 时抛出 McpException 且状态码正确`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val error = captureError { realIo { client().listTools() } }

        assertNotNull(error)
        assertTrue(error is McpException)
        assertEquals(404, (error as McpException).statusCode)
    }

    @Test
    fun `带 Bearer token 时请求头带上鉴权`() = runTest {
        server.dispatcher = McpDispatcher()
        val config = McpServerConfig(
            id = "srv-1",
            name = "demo server",
            url = server.url("/mcp").toString(),
            bearerToken = "tok-123",
        )
        val client = McpClient(config, OkHttpHttp())

        realIo { client.connect() }

        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `McpTool 把工具调用结果转成 ToolResult`() = runTest {
        server.dispatcher = McpDispatcher()
        val client = client()
        realIo { client.connect() }
        val tool = McpToolFactory.create(client, McpToolInfo(name = "echo", description = "回声"))

        assertEquals("demo_server__echo", tool.spec.name)
        assertEquals("回声", tool.spec.description)

        val result = realIo {
            tool.execute(buildJsonObject { put("text", "hi") }, TestToolContext())
        }
        assertFalse(result.isError)
        assertEquals("工具输出", result.content)
    }

    /** MCP 工具执行只需要一个上下文，这里给一个最小实现。 */
    private class TestToolContext : com.mynok.myagent.core.tools.ToolContext {
        override val http = com.mynok.myagent.core.testing.FakeHttp()
        override val approver = com.mynok.myagent.core.tools.ToolApprover { true }
        override val workspace = com.mynok.myagent.core.testing.FakeWorkspace
        override fun log(message: String) = Unit
    }
}
