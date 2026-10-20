package com.mynok.myagent.core.model.gemini

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
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemini 原生协议的报文与事件映射。 */
class GeminiProviderProtocolTest {

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

    private fun profile(baseUrl: String = "https://generativelanguage.googleapis.com") = ModelProfile(
        id = "p",
        name = "gemini",
        providerType = ProviderType.GEMINI,
        baseUrl = baseUrl,
        modelId = "gemini-2.5-flash",
        maxOutputTokens = 1024,
    )

    private val sse = """
        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"你好"}]}}]}

        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"想一下","thought":true}]}}]}

        data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"北京"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":20,"totalTokenCount":30}}

    """.trimIndent()

    private fun request() = ModelRequest(
        messages = listOf(
            ChatMessage.user("查天气"),
            ChatMessage(
                role = Role.ASSISTANT,
                toolCalls = listOf(
                    ToolCall(id = "call_get_weather_0", name = "get_weather", arguments = """{"city":"北京"}"""),
                ),
            ),
            ChatMessage(
                role = Role.TOOL,
                parts = listOf(ContentPart.Text("晴 25℃")),
                name = "get_weather",
                toolCallId = "call_get_weather_0",
            ),
        ),
        system = "你是助手",
        maxTokens = 1024,
        tools = listOf(
            ToolSpec(name = "get_weather", description = "查天气", parameters = JsonObject(emptyMap())),
        ),
    )

    @Test
    fun streamMapsEvents() = runTest {
        val provider = GeminiProvider(profile(), FakeHttp(streamBody = sse), "secret-key")
        val events = provider.stream(request()).toList()

        assertEquals(listOf("你好"), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertEquals(listOf("想一下"), events.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })

        val call = events.filterIsInstance<ModelEvent.ToolCallDelta>().single()
        assertEquals(0, call.index)
        assertEquals("call_get_weather_0", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"北京"}""", call.argumentsDelta)

        val usage = events.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(10, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(30, usage.totalTokens)

        val completed = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(FinishReason.STOP, completed.finishReason)
        assertTrue(events.last() is ModelEvent.Completed)
    }

    @Test
    fun requestBodyFollowsGeminiProtocol() = runTest {
        val http = FakeHttp(streamBody = sse)
        val provider = GeminiProvider(profile(), http, "secret-key")
        provider.stream(request()).toList()

        val req = http.lastRequest!!
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            req.url,
        )
        assertEquals("secret-key", req.headers["x-goog-api-key"])

        val body = MyAgentJson.parseToJsonElement(req.body!!).jsonObject

        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("你是助手", system["text"]!!.jsonPrimitive.content)

        val contents = body["contents"]!!.jsonArray
        assertEquals(
            listOf("user", "model", "user"),
            contents.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )

        val assistantParts = contents[1].jsonObject["parts"]!!.jsonArray
        val fc = assistantParts[0].jsonObject["functionCall"]!!.jsonObject
        assertEquals("get_weather", fc["name"]!!.jsonPrimitive.content)
        assertEquals("北京", fc["args"]!!.jsonObject["city"]!!.jsonPrimitive.content)

        val toolParts = contents[2].jsonObject["parts"]!!.jsonArray
        val fr = toolParts[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_weather", fr["name"]!!.jsonPrimitive.content)
        assertEquals("晴 25℃", fr["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)

        val decls = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, decls.size)
        assertEquals("get_weather", decls[0].jsonObject["name"]!!.jsonPrimitive.content)

        val gen = body["generationConfig"]!!.jsonObject
        assertEquals(1024, gen["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun baseUrlWithV1BetaIsNotDuplicated() = runTest {
        val http = FakeHttp(streamBody = sse)
        val provider = GeminiProvider(
            profile("https://generativelanguage.googleapis.com/v1beta"),
            http,
            "secret-key",
        )
        provider.stream(request()).toList()
        assertTrue(
            http.lastRequest!!.url.startsWith(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:",
            ),
        )
    }

    @Test
    fun httpErrorBecomesFailedEvent() = runTest {
        val provider = GeminiProvider(profile(), FakeHttp(code = 429), "secret-key")
        val events = provider.stream(request()).toList()
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertTrue(failed.error is ModelException)
        assertEquals(429, (failed.error as ModelException).statusCode)
    }

    @Test
    fun completeParsesSingleResponse() = runTest {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"晴 25℃"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}}
        """.trimIndent()
        val http = FakeHttp(body = body)
        val provider = GeminiProvider(profile(), http, "secret-key")
        val result = provider.complete(request())
        assertEquals("晴 25℃", result.message.text)
        assertEquals(FinishReason.STOP, result.finishReason)
        assertEquals(7, result.usage?.totalTokens)
        assertTrue(http.lastRequest!!.url.endsWith(":generateContent"))
    }
}
