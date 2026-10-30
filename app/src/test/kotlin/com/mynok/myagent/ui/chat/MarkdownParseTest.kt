package com.mynok.myagent.ui.chat

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Markdown 解析的分句测试。
 *
 * 纯逻辑为主；行内解析要构造 AnnotatedString，跑在 Robolectric 上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTextApi::class)
class MarkdownParseTest {

    @Test
    fun `段落按空行分段`() {
        val out = parseMarkdown("第一段\n继续第一段\n\n第二段")
        assertEquals(2, out.size)
        assertEquals("第一段\n继续第一段", (out[0] as MdBlock.Paragraph).text)
        assertEquals("第二段", (out[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `标题级别与内容`() {
        val out = parseMarkdown("# 一\n## 二\n### 三")
        assertEquals(MdBlock.Heading(1, "一"), out[0])
        assertEquals(MdBlock.Heading(2, "二"), out[1])
        assertEquals(MdBlock.Heading(3, "三"), out[2])
    }

    @Test
    fun `列表带缩进层级与有序编号`() {
        val out = parseMarkdown("- 一级\n  - 二级\n  - 也是二级\n3. 有序第三")
        assertEquals(MdBlock.Bullet(0, null, "一级"), out[0])
        assertEquals(MdBlock.Bullet(1, null, "二级"), out[1])
        assertEquals(MdBlock.Bullet(1, null, "也是二级"), out[2])
        assertEquals(MdBlock.Bullet(0, "3.", "有序第三"), out[3])
    }

    @Test
    fun `列表续行并到同一项`() {
        val out = parseMarkdown("- 第一行\n第二行")
        assertEquals(1, out.size)
        assertEquals("第一行\n第二行", (out[0] as MdBlock.Bullet).text)
    }

    @Test
    fun `引用块剥掉前缀`() {
        val out = parseMarkdown("> 引用第一\n> 引用第二")
        assertEquals("引用第一\n引用第二", (out[0] as MdBlock.Quote).text)
    }

    @Test
    fun `分隔线识别成 Rule`() {
        val out = parseMarkdown("---\n内容")
        assertEquals(MdBlock.Rule, out[0])
        assertEquals("内容", (out[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `表格丢掉分隔行`() {
        val out = parseMarkdown("| 名字 | 年龄 |\n|---|---|\n| 张三 | 10 |")
        val table = out[0] as MdBlock.Table
        assertEquals(listOf("名字", "年龄"), table.header)
        assertEquals(listOf(listOf("张三", "10")), table.rows)
    }

    @Test
    fun `普通竖线别误判成表格`() {
        val out = parseMarkdown("a | b | c")
        assertEquals("a | b | c", (out[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `代码块保留内容与语言`() {
        val out = parseMarkdown("```kotlin\nval x = 1\n```")
        assertEquals(MdBlock.Code("val x = 1", "kotlin"), out[0])
    }

    @Test
    fun `代码第一行不会被误当成语言标记`() {
        val out = parseMarkdown("```\necho hi\n```")
        assertEquals(MdBlock.Code("echo hi", null), out[0])
    }

    @Test
    fun `粗体斜体行内代码都被标出来`() {
        val s = parseInline("这是**粗体**和*斜体*还有`代码`", {})
        assertEquals("这是粗体和斜体还有代码", s.text)
        val spans = s.spanStyles.map { it.item }
        assertTrue("应有粗体", spans.any { it.fontWeight == FontWeight.Bold })
        assertTrue("应有斜体", spans.any { it.fontStyle == FontStyle.Italic })
        assertTrue("应有等宽", spans.any { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `方括号链接用显示文字并回调地址`() {
        var url: String? = null
        val s = parseInline("看 [官网](https://a.com/x) 吧") { u -> url = u }
        assertEquals("看 官网 吧", s.text)
        val link =
            s.getLinkAnnotations(0, s.length).first { it.item is LinkAnnotation.Url }.item
                as LinkAnnotation.Url
        assertEquals("https://a.com/x", link.url)
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://a.com/x", url)
    }

    @Test
    fun `裸网址结尾的中文标点不算进网址`() {
        val s = parseInline("看 https://a.com/x，然后", {})
        val link =
            s.getLinkAnnotations(0, s.length).first { it.item is LinkAnnotation.Url }.item
                as LinkAnnotation.Url
        assertEquals("https://a.com/x", link.url)
        assertEquals("看 https://a.com/x，然后", s.text)
    }

    @Test
    fun `snake case 不被当成斜体`() {
        val s = parseInline("some_variable_name 保持原样", {})
        assertEquals("some_variable_name 保持原样", s.text)
        assertTrue(
            "不该有斜体",
            s.spanStyles.none { it.item.fontStyle == FontStyle.Italic },
        )
    }

    @Test
    fun `乘号不误判成斜体`() {
        val s = parseInline("计算 2 * 3 * 4 的结果", {})
        assertEquals("计算 2 * 3 * 4 的结果", s.text)
    }

    @Test
    fun `删除线`() {
        val s = parseInline("~~删掉~~留着", {})
        assertEquals("删掉留着", s.text)
        assertTrue(
            "应有删除线装饰",
            s.spanStyles.any { range ->
                range.item.textDecoration?.contains(TextDecoration.LineThrough) == true
            },
        )
    }
}
