package com.mynok.myagent.core.model

import com.mynok.myagent.core.model.openai.OpenAICompatProvider
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量输出的回归测试。
 *
 * 之前的实现会把整段 SSE 缓存到响应结束后再解析，表现就是「一直转圈，
 * 最后一次性蹦出全文」。这里用一个会「卡住等下一条」的假 Http 证明：
 * 第一条事件必须在响应还没结束时就已经到达消费方。
 */
class StreamingSseTest {

    private val profile = ModelProfile(
        id = "p",
        name = "fake",
        providerType = ProviderType.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:1",
        modelId = "fake-model",
        maxOutputTokens = 1024,
    )

    /** 第二条数据要等消费方收到第一条之后才发，模拟真实的逐块推送。 */
    private class GatedHttp(private val gate: CompletableDeferred<Unit>) : Http {

        override suspend fun execute(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("stream() 不应该走 execute")

        override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse {
            onChunk("""data: {"choices":[{"delta":{"content":"第一段"}}]}""" + "\n\n")
            withTimeout(5_000) { gate.await() }
            onChunk("""data: {"choices":[{"delta":{"content":"第二段"}}]}""" + "\n\n")
            onChunk("data: [DONE]\n\n")
            return HttpResponse(code = 200)
        }

        override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> =
            throw UnsupportedOperationException("用不到")
    }

    @Test
    fun `首条文本不必等整段响应结束`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = OpenAICompatProvider(profile, GatedHttp(gate), apiKey = null)
        val received = mutableListOf<String>()

        val events = provider.stream(
            ModelRequest(messages = listOf(ChatMessage.user("hi")), stream = true),
        ).onEach { event ->
            if (event is ModelEvent.TextDelta) {
                received += event.text
                // 收到第一段就放行第二段；若实现是「攒完再解析」，这里永远走不到
                if (received.size == 1) gate.complete(Unit)
            }
        }.toList()

        assertEquals(listOf("第一段", "第二段"), received)
        assertTrue("流最后必须是 Completed", events.last() is ModelEvent.Completed)
    }

    @Test
    fun `非 2xx 时带上响应体且不产生增量事件`() = runTest {
        val http = object : Http {
            override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(200)
            override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse =
                HttpResponse(code = 401, body = """{"error":{"message":"invalid api key"}}""")

            override suspend fun downloadBase64(request: HttpRequest) = "" to ""
        }
        val provider = OpenAICompatProvider(profile, http, apiKey = "bad")

        val events = provider.stream(
            ModelRequest(messages = listOf(ChatMessage.user("hi")), stream = true),
        ).toList()

        assertEquals(1, events.size)
        val failure = events.single() as ModelEvent.Failed
        val error = failure.error as ModelException
        assertEquals(401, error.statusCode)
        assertTrue("错误信息里应带响应体：${error.message}", error.message!!.contains("invalid api key"))
    }
}
