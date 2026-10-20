package com.mynok.myagent.core.model.openai

import app.cash.turbine.test
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelException
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.ToolCall
import com.mynok.myagent.core.model.ToolCallAccumulator
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.OkHttpHttp
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OpenAI 兼容协议的端到端测试：用 MockWebServer 起一个假的服务端。
 */
class OpenAICompatProviderTest {

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

    // ------------------------------------------------------------------ 工具方法

    private fun profile() = ModelProfile(
        id = "p",
        name = "openai-compatible",
        providerType = ProviderType.OPENAI_COMPAT,
        baseUrl = server.url("/").toString().trimEnd('/'),
        modelId = "gpt-4o-mini",
        maxOutputTokens = 1024,
    )

    private fun provider() = OpenAICompatProvider(profile(), OkHttpHttp(), "sk-test")

    private fun sse(vararg lines: String): String = lines.joinToString("\n\n", postfix = "\n\n")

    private fun request(
        messages: List<ChatMessage> = listOf(ChatMessage.user("你好")),
        system: String? = null,
        tools: List<ToolSpec> = emptyList(),
    ) = ModelRequest(messages = messages, system = system, tools = tools, stream = true)

    /**
     * 流式错误统一以 `ModelEvent.Failed` 收尾，而不是抛异常——
     * 否则 agent 循环拿不到收尾事件会一直等下去（与 Anthropic/Gemini 一致）。
     */
    private suspend fun captureFailure(block: suspend () -> List<ModelEvent>): ModelException? =
        block().filterIsInstance<ModelEvent.Failed>().lastOrNull()?.error as? ModelException

    // ------------------------------------------------------------------ 用例

    @Test
    fun `SSE 文本增量被拼接成完整回复`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}""",
                    """data: {"id":"1","choices":[{"index":0,"delta":{"content":"，世"}}]}""",
                    """data: {"id":"1","choices":[{"index":0,"delta":{"content":"界"}}]}""",
                ),
            ),
        )

        val events = provider().stream(request()).toList()

        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("你好，世界", text)
    }

    @Test
    fun `工具调用的分片被拼成一个完整 ToolCall`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"北京\"}"}}]}}]}""",
                ),
            ),
        )

        val events = provider().stream(request()).toList()

        val accumulator = ToolCallAccumulator()
        events.filterIsInstance<ModelEvent.ToolCallDelta>().forEach { accumulator.onDelta(it) }
        val call = accumulator.build().single()

        assertEquals("call_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("{\"city\":\"北京\"}", call.arguments)
        assertTrue(call.isComplete)
    }

    @Test
    fun `多个 index 的工具调用分片分别还原`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"alpha","arguments":"{}"}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"beta","arguments":"{}"}}]}}]}""",
                ),
            ),
        )

        val events = provider().stream(request()).toList()
        val accumulator = ToolCallAccumulator()
        events.filterIsInstance<ModelEvent.ToolCallDelta>().forEach { accumulator.onDelta(it) }
        val calls = accumulator.build()

        assertEquals(listOf("alpha", "beta"), calls.map { it.name })
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
    }

    @Test
    fun `DONE 收尾后流以 Completed 结束`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "data: [DONE]",
                ),
            ),
        )

        val events = provider().stream(request()).toList()

        val completed = events.filterIsInstance<ModelEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(FinishReason.STOP, completed.single().finishReason)
        assertTrue("流的最后一个事件必须是 Completed", events.last() is ModelEvent.Completed)
        assertEquals("hi", events.filterIsInstance<ModelEvent.TextDelta>().single().text)
    }

    @Test
    fun `流式事件可以逐个消费`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"你好"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"content":"世界"}}]}""",
                ),
            ),
        )

        provider().stream(request()).test {
            assertEquals(ModelEvent.TextDelta("你好"), awaitItem())
            assertEquals(ModelEvent.TextDelta("世界"), awaitItem())
            assertEquals(ModelEvent.Completed(FinishReason.STOP), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `HTTP 401 以 Failed 事件返回 ModelException 且 statusCode 为 401`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}"""),
        )

        val events = provider().stream(request()).toList()
        val error = events.filterIsInstance<ModelEvent.Failed>().lastOrNull()?.error

        assertNotNull("401 应当以 Failed 事件返回错误", error)
        assertTrue("实际是 ${error!!::class.simpleName}", error is ModelException)
        assertEquals(401, (error as ModelException).statusCode)
        assertTrue(
            "错误信息里应带服务端返回体，实际：${error.message}",
            error.message!!.contains("invalid api key"),
        )
        assertTrue("最后一个事件必须是 Failed", events.last() is ModelEvent.Failed)
    }

    @Test
    fun `HTTP 500 以 Failed 事件返回 ModelException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = captureFailure { provider().stream(request()).toList() }

        assertNotNull("500 应当以 Failed 事件返回", error)
        assertEquals(500, error!!.statusCode)
    }

    @Test
    fun `响应体里带 error 对象时以 Failed 事件返回 ModelException`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(
                    """data: {"error":{"message":"模型不可用","type":"server_error"}}""",
                ),
            ),
        )

        val error = captureFailure { provider().stream(request()).toList() }

        assertNotNull("响应体里的 error 对象应当以 Failed 事件返回", error)
        assertTrue(
            "异常信息里应包含服务端给的错误描述，实际：${error!!.message}",
            error.message!!.contains("模型不可用"),
        )
    }

    @Test
    fun `请求体包含 model messages tools 与 stream 标志`() = runTest {
        server.enqueue(MockResponse().setBody(sse("data: [DONE]")))

        provider().stream(
            request(
                system = "你是助手",
                tools = listOf(
                    ToolSpec(
                        name = "get_weather",
                        description = "查天气",
                        parameters = JsonObject(emptyMap()),
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)

        val body = MyAgentJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertTrue("stream 标志应为 true", body["stream"]!!.jsonPrimitive.boolean)

        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("你是助手", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("你好", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val function = tools[0].jsonObject["function"]!!.jsonObject
        assertEquals("get_weather", function["name"]!!.jsonPrimitive.content)
        assertEquals("查天气", function["description"]!!.jsonPrimitive.content)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `带图片的消息 content 是数组且含 image_url`() = runTest {
        server.enqueue(MockResponse().setBody(sse("data: [DONE]")))

        provider().stream(
            request(
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        parts = listOf(
                            ContentPart.Text("这张图里是什么"),
                            ContentPart.Image(mimeType = "image/png", base64 = "QUJD"),
                        ),
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = MyAgentJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这张图里是什么", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue("image url 应以 data: 开头，实际：$url", url.startsWith("data:"))
        assertEquals("data:image/png;base64,QUJD", url)
    }

    @Test
    fun `工具结果消息 role 为 tool 且带 tool_call_id`() = runTest {
        server.enqueue(MockResponse().setBody(sse("data: [DONE]")))

        provider().stream(
            request(
                messages = listOf(
                    ChatMessage.user("查天气"),
                    ChatMessage(
                        role = Role.ASSISTANT,
                        toolCalls = listOf(ToolCall(id = "call_1", name = "get_weather", arguments = "{\"city\":\"北京\"}")),
                    ),
                    ChatMessage(
                        role = Role.TOOL,
                        parts = listOf(ContentPart.Text("晴 25℃")),
                        name = "get_weather",
                        toolCallId = "call_1",
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = MyAgentJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray

        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })

        val toolMessage = messages[2].jsonObject
        assertEquals("call_1", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("晴 25℃", toolMessage["content"]!!.jsonPrimitive.content)

        val assistant = messages[1].jsonObject
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val function = toolCalls[0].jsonObject["function"]!!.jsonObject
        assertEquals("get_weather", function["name"]!!.jsonPrimitive.content)
        assertEquals("{\"city\":\"北京\"}", function["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非流式 complete 解析出文本与工具调用`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"晴 25℃",
                 "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}]},
                 "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}
                """.trimIndent(),
            ),
        )

        val result = provider().complete(request())

        assertEquals("晴 25℃", result.message.text)
        assertEquals(Role.ASSISTANT, result.message.role)
        assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        val call = result.message.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals(12, result.usage!!.totalTokens)
    }
}
