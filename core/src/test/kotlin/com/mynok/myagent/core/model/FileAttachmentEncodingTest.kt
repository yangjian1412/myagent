package com.mynok.myagent.core.model

import com.mynok.myagent.core.model.anthropic.AnthropicProvider
import com.mynok.myagent.core.model.gemini.GeminiProvider
import com.mynok.myagent.core.model.openai.OpenAICompatProvider
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

/**
 * 文件附件在三家协议里的编码。
 *
 * 规则：文本类附件（代码、配置）转成文本块，所有模型都读得懂；
 * PDF 之类的二进制才走各家的文档格式。
 */
class FileAttachmentEncodingTest {

    private class FakeHttp : Http {
        var lastRequest: HttpRequest? = null

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            return HttpResponse(code = 200, body = "")
        }

        override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse {
            lastRequest = request
            return HttpResponse(code = 200, body = "")
        }

        override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> = "" to ""
    }

    private fun profile(type: ProviderType) = ModelProfile(
        id = "p",
        name = "test",
        providerType = type,
        baseUrl = "https://example.com",
        modelId = "test-model",
        maxOutputTokens = 1024,
    )

    private fun messageOf(vararg parts: ContentPart) = ChatMessage(role = Role.USER, parts = parts.toList())

    private fun bodyOf(request: HttpRequest?): JsonObject =
        MyAgentJson.parseToJsonElement(request!!.body!!).jsonObject

    /** OpenAI 兼容：第一条消息的 content 数组。 */
    private fun openAiContent(body: JsonObject) =
        body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

    private suspend fun openAiPartOf(vararg parts: ContentPart) =
        openAiContent(bodyOf(streamOn(ProviderType.OPENAI_COMPAT, *parts))).let { it to it.size }

    private suspend fun streamOn(type: ProviderType, vararg parts: ContentPart): HttpRequest? {
        val http = FakeHttp()
        val request = ModelRequest(messages = listOf(messageOf(*parts)))
        when (type) {
            ProviderType.OPENAI_COMPAT -> OpenAICompatProvider(profile(type), http, "k").stream(request).toList()
            ProviderType.ANTHROPIC -> AnthropicProvider(profile(type), http, "k").stream(request).toList()
            ProviderType.GEMINI -> GeminiProvider(profile(type), http, "k").stream(request).toList()
        }
        return http.lastRequest
    }

    // ------------------------------------------------------------ OpenAI 兼容

    @Test
    fun `OpenAI 兼容把文本附件转成文本块`() = runTest {
        val (content, size) = openAiPartOf(
            ContentPart.Text("这段代码讲了什么"),
            ContentPart.File("App.kt", "text/x-kotlin", "ZnVuIG1haW4oKSB7fQ=="),
        )

        assertEquals(2, size)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val text = content[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("应带上文件名，实际：$text", text.contains("App.kt"))
        assertTrue("应带上文件内容，实际：$text", text.contains("fun main() {}"))
    }

    @Test
    fun `OpenAI 兼容把 PDF 发成 file 内容块`() = runTest {
        val (content, size) = openAiPartOf(
            ContentPart.Text("总结一下"),
            ContentPart.File("合同.pdf", "application/pdf", "SlZC"),
        )

        assertEquals(2, size)
        assertEquals("file", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val file = content[1].jsonObject["file"]!!.jsonObject
        assertEquals("合同.pdf", file["filename"]!!.jsonPrimitive.content)
        assertEquals(
            "data:application/pdf;base64,SlZC",
            file["file_data"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------ Anthropic

    private suspend fun anthropicBlocks(vararg parts: ContentPart) =
        bodyOf(streamOn(ProviderType.ANTHROPIC, *parts))["messages"]!!
            .jsonArray[0].jsonObject["content"]!!.jsonArray

    @Test
    fun `Anthropic 把文本附件转成文本块`() = runTest {
        val blocks = anthropicBlocks(
            ContentPart.Text("这份配置对不对"),
            ContentPart.File("app.yaml", "application/x-yaml", "a2V5OiB2YWx1ZQ=="),
        )

        assertEquals(2, blocks.size)
        assertEquals("text", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        val text = blocks[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("应带上文件名，实际：$text", text.contains("app.yaml"))
        assertTrue("应带上文件内容，实际：$text", text.contains("key: value"))
    }

    @Test
    fun `Anthropic 把 PDF 发成 document 块`() = runTest {
        val blocks = anthropicBlocks(
            ContentPart.Text("总结一下"),
            ContentPart.File("合同.pdf", "application/pdf", "SlZC"),
        )

        assertEquals(2, blocks.size)
        assertEquals("document", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("合同.pdf", blocks[1].jsonObject["title"]!!.jsonPrimitive.content)
        val source = blocks[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("SlZC", source["data"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ Gemini

    private suspend fun geminiParts(vararg parts: ContentPart) =
        bodyOf(streamOn(ProviderType.GEMINI, *parts))["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray

    @Test
    fun `Gemini 把文本附件转成文本块`() = runTest {
        val parts = geminiParts(
            ContentPart.Text("这段代码讲了什么"),
            ContentPart.File("App.kt", "text/x-kotlin", "ZnVuIG1haW4oKSB7fQ=="),
        )

        assertEquals(2, parts.size)
        val text = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("应带上文件名，实际：$text", text.contains("App.kt"))
        assertTrue("应带上文件内容，实际：$text", text.contains("fun main() {}"))
    }

    @Test
    fun `Gemini 把 PDF 发成 inlineData`() = runTest {
        val parts = geminiParts(
            ContentPart.Text("总结一下"),
            ContentPart.File("合同.pdf", "application/pdf", "SlZC"),
        )

        assertEquals(2, parts.size)
        val inline = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("application/pdf", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("SlZC", inline["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Gemini 遇到不支持的类型降级成说明文本`() = runTest {
        val parts = geminiParts(
            ContentPart.Text("总结一下"),
            ContentPart.File("演示.pptx", "application/octet-stream", "UG9X"),
        )

        assertEquals(2, parts.size)
        val text = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        // 不能静默丢掉附件，得让模型知道有个文件没发出去
        assertTrue("应说明附件没发出去，实际：$text", text.contains("没有发出去"))
        assertTrue("应带上文件名，实际：$text", text.contains("演示.pptx"))
    }
}
