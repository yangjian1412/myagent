package com.mynok.myagent.core.model

import com.mynok.myagent.core.model.ContentParts.MAX_TEXT_CHARS
import com.mynok.myagent.core.model.ContentParts.asTextContent
import com.mynok.myagent.core.model.ContentParts.isTextual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 附件「能不能当文本发」的判断。
 *
 * 系统给的类型经常等于没说，所以这里重点测扩展名优先与字节兜底两条路。
 */
class ContentPartsTest {

    private fun fileOf(name: String, mimeType: String, content: String) = ContentPart.File(
        name = name,
        mimeType = mimeType,
        base64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)),
    )

    private fun fileOfBytes(name: String, mimeType: String, bytes: ByteArray) = ContentPart.File(
        name = name,
        mimeType = mimeType,
        base64 = Base64.getEncoder().encodeToString(bytes),
    )

    // ------------------------------------------------------------ isTextual

    @Test
    fun `文本 MIME 判为文本`() {
        listOf(
            "text/plain" to "note.txt",
            "text/markdown" to "README.md",
            // MIME 带参数也要认出来
            "text/plain; charset=utf-8" to "note.txt",
        ).forEach { (mime, name) ->
            assertTrue("$mime 应判为文本", isTextual(mime, name))
        }
    }

    @Test
    fun `类型缺失时按扩展名判断`() {
        // 内容管理器给不出类型（空串或 octet-stream）时，扩展名是唯一的线索
        assertTrue(isTextual("", "main.py"))
        assertTrue(isTextual("application/octet-stream", "main.py"))
        assertTrue(isTextual("application/octet-stream", "docker-compose.yaml"))
    }

    @Test
    fun `没有扩展名的文本文件也认得`() {
        // Makefile、Dockerfile 这类名字本身就是类型，没有点可找
        assertTrue(isTextual("", "Makefile"))
        assertTrue(isTextual("application/octet-stream", "Dockerfile"))
        assertTrue(isTextual("", "README"))
        // 反过来，没有扩展名又不认识的就不能乱认
        assertFalse(isTextual("application/octet-stream", "mystery"))
    }

    @Test
    fun `图片与 PDF 不判为文本`() {
        assertFalse(isTextual("image/png", "截图.png"))
        assertFalse(isTextual("application/pdf", "合同.pdf"))
        assertFalse(isTextual("application/octet-stream", "演示.pptx"))
    }

    @Test
    fun `application 里确实是文本的那几个也算文本`() {
        assertTrue(isTextual("application/json", "data.json"))
        assertTrue(isTextual("application/x-yaml", "conf.yml"))
        // 光看 MIME 就够，扩展名换成没见过的也认
        assertTrue(isTextual("application/json", "unknown-extension.wat"))
    }

    @Test
    fun `扩展名和大写都算`() {
        assertTrue(isTextual("application/octet-stream", "MAIN.PY"))
        assertTrue(isTextual("application/octet-stream", "README.Md"))
    }

    // ------------------------------------------------------------ asTextContent

    @Test
    fun `文本附件转成带文件名的文本`() {
        val text = fileOf("App.kt", "text/x-kotlin", "fun main() {}").asTextContent()
        assertNotNull(text)
        assertTrue("应带上文件名，实际：$text", text!!.contains("App.kt"))
        assertTrue("应带上内容，实际：$text", text.contains("fun main() {}"))
    }

    @Test
    fun `中文内容原样保留`() {
        val text = fileOf("说明.txt", "text/plain", "你好，世界").asTextContent()
        assertNotNull(text)
        assertTrue("中文不该被转义，实际：$text", text!!.contains("你好，世界"))
    }

    @Test
    fun `PDF 不转文本交给文档格式处理`() {
        assertNull(fileOf("合同.pdf", "application/pdf", "JVBERi0xLjQK").asTextContent())
    }

    @Test
    fun `扩展名骗人的时候按字节退回文档格式`() {
        // 名字叫 .json，内容其实是二进制：合法 ASCII 与非法字节交替，
        // 0xB5 是 UTF-8 的后续字节，单独出现一定解不出字符
        val notText = ByteArray(24) { if (it % 2 == 0) '.'.code.toByte() else 0xB5.toByte() }
        val binary = fileOfBytes("data.json", "application/json", notText)
        assertNull(binary.asTextContent())
    }

    @Test
    fun `base64 解不开时退回文档格式`() {
        val broken = ContentPart.File("a.txt", "text/plain", "这不是合法的 base64 ！")
        assertNull(broken.asTextContent())
    }

    @Test
    fun `超长文本会截断并说明`() {
        val long = "字".repeat(MAX_TEXT_CHARS + 5_000)
        val text = fileOf("大文件.txt", "text/plain", long).asTextContent()
        assertNotNull(text)
        assertTrue("该说明截断了，实际结尾：${text!!.takeLast(40)}", text.contains("截断"))
        // 正文部分不该超过上限，加上说明的余量即可
        assertTrue("截断后仍太长：${text.length}", text.length < MAX_TEXT_CHARS + 200)
    }

    @Test
    fun `没超长的文本不加截断说明`() {
        val text = fileOf("小文件.txt", "text/plain", "just right").asTextContent()
        assertNotNull(text)
        assertFalse("不该出现截断说明，实际：$text", text!!.contains("截断"))
    }

    @Test
    fun `空文件也给出内容段而不是空`() {
        // 空附件不该让协议层收到一个空文本块
        val text = fileOf("空的.txt", "text/plain", "").asTextContent()
        assertNotNull(text)
        assertEquals(false, text!!.isEmpty())
    }
}
