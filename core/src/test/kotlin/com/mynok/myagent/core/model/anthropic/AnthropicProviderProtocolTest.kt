package com.mynok.myagent.core.model.anthropic

import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicProviderProtocolTest {

    private class FakeHttp(
        private val code: Int = 200,
        private val streamBody: String = "",
        private val body: String = "",
    ) : Http {
        var lastRequest: HttpRequest? = null

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            return HttpResponse(code = code, body = body)
        }

        override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse {
            lastRequest = request
            onChunk(streamBody)
            return HttpResponse(code = code, body = "")
        }

        override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> = "" to ""
    }

    private fun profile() = ModelProfile(
        id = "p",
        name = "claude",
        providerType = ProviderType.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        modelId = "claude-sonnet-4",
        maxOutputTokens = 1024,
    )

    private val sse = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":11,"output_tokens":1}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想一下"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"search","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"猫\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    @Test
    fun `stream 映射事件`() = runTest {
        val http = FakeHttp(streamBody = sse)
        val provider = AnthropicProvider(profile(), http, "sk-test")
        val events = provider.stream(
            ModelRequest(
                messages = listOf(com.mynok.myagent.core.model.ChatMessage.user("hi")),
                system = "你是助手",
            ),
        ).toList()

        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("你好", text)
        assertEquals("想一下", events.filterIsInstance<ModelEvent.ReasoningDelta>().joinToString("") { it.text })

        val toolStart = events.filterIsInstance<ModelEvent.ToolCallDelta>().first()
        assertEquals("toolu_1", toolStart.id)
        assertEquals("search", toolStart.name)
        assertEquals(null, toolStart.argumentsDelta)
        val args = events.filterIsInstance<ModelEvent.ToolCallDelta>()
            .mapNotNull { it.argumentsDelta }.joinToString("")
        assertEquals("{\"q\":\"猫\"}", args)

        val usage = events.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(11, usage.promptTokens)
        assertEquals(42, usage.completionTokens)
        assertEquals(53, usage.totalTokens)

        val completed = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(FinishReason.TOOL_CALLS, completed.finishReason)
        assertTrue(events.last() is ModelEvent.Completed)

        // 请求头 / 请求体
        val req = http.lastRequest!!
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals("sk-test", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        val body = req.body!!
        assertTrue(body.contains("\"system\":\"你是助手\""))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"model\":\"claude-sonnet-4\""))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun `http 错误转成 Failed`() = runTest {
        val http = FakeHttp(code = 401, streamBody = "{\"error\":{\"message\":\"bad key\"}}")
        val provider = AnthropicProvider(profile(), http, null)
        val events = provider.stream(ModelRequest(messages = emptyList())).toList()
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals(401, (failed.error as com.mynok.myagent.core.model.ModelException).statusCode)
        assertTrue(events.last() is ModelEvent.Failed)
    }

    @Test
    fun `sse error 事件转成 Failed`() = runTest {
        val http = FakeHttp(streamBody = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
        val provider = AnthropicProvider(profile(), http, null)
        val events = provider.stream(ModelRequest(messages = emptyList())).toList()
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertTrue(failed.error.message!!.contains("Overloaded"))
    }

    @Test
    fun `complete 解析 content 数组`() = runTest {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4",
             "content":[{"type":"text","text":"结果"},
                        {"type":"tool_use","id":"toolu_9","name":"search","input":{"q":"猫"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":5,"output_tokens":7}}
        """.trimIndent()
        val http = FakeHttp(body = body)
        val provider = AnthropicProvider(profile(), http, "k")
        val result = provider.complete(ModelRequest(messages = emptyList()))
        assertEquals("结果", result.message.text)
        assertEquals(Role.ASSISTANT, result.message.role)
        val call = result.message.toolCalls.single()
        assertEquals("toolu_9", call.id)
        assertEquals("search", call.name)
        assertEquals("{\"q\":\"猫\"}", call.arguments)
        assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        assertEquals(12, result.usage!!.totalTokens)
        assertTrue(http.lastRequest!!.body!!.contains("\"stream\":false"))
    }

    @Test
    fun `工具结果消息编码成 user 的 tool_result`() {
        val http = FakeHttp(body = "{}")
        val provider = AnthropicProvider(profile(), http, "k")
        val body = provider.buildRequestBody(
            ModelRequest(
                messages = listOf(
                    com.mynok.myagent.core.model.ChatMessage.user("hi"),
                    com.mynok.myagent.core.model.ChatMessage(
                        role = Role.ASSISTANT,
                        toolCalls = listOf(
                            com.mynok.myagent.core.model.ToolCall("toolu_1", "search", "{\"q\":\"x\"}"),
                        ),
                    ),
                    com.mynok.myagent.core.model.ChatMessage(
                        role = Role.TOOL,
                        parts = listOf(com.mynok.myagent.core.model.ContentPart.Text("搜到了")),
                        toolCallId = "toolu_1",
                    ),
                ),
            ),
        ).toString()
        assertTrue(body.contains("\"type\":\"tool_use\""))
        assertTrue(body.contains("\"tool_use_id\":\"toolu_1\""))
        assertTrue(body.contains("搜到了"))
        assertTrue(body.contains("\"input\":{\"q\":\"x\"}"))
        // tool_result 属于 role=user 的消息
        assertTrue(body.contains("\"role\":\"user\",\"content\":[{\"type\":\"tool_result\""))
    }
}
