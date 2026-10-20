package com.mynok.myagent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析器测试。
 *
 * 覆盖：单条 data、event+data 组合、多事件分隔、跨 chunk 分块、注释行、finish 兜底。
 */
class SseParserTest {

    @Test
    fun `单条 data 事件被解析出来`() {
        val parser = SseParser()
        val events = parser.onChunk("data: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertNull(events[0].name)
        // SSE 规范里 data 不应带尾随换行。
        // TODO 被测代码已知问题：SseParser.onChunk 走「空行结束」分支时没有去掉
        // dataBuffer 末尾的 '\n'，而 finish() 分支却去掉了，两条路径行为不一致。
        // 这里按正确行为断言，用例当前会失败。
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `event 与 data 组合时事件名被保留`() {
        val parser = SseParser()
        val events = parser.onChunk("event: message\ndata: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].name)
        assertEquals("{\"a\":1}", events[0].data.trim())
    }

    @Test
    fun `空行分隔的多条事件都被解析出来`() {
        val raw = "data: 第一条\n\nevent: ping\ndata: 第二条\n\ndata: 第三条\n\n"
        val parser = SseParser()
        val events = parser.onChunk(raw)
        assertEquals(3, events.size)
        assertEquals("第一条", events[0].data.trim())
        assertEquals("ping", events[1].name)
        assertEquals("第二条", events[1].data.trim())
        assertEquals("第三条", events[2].data.trim())
        assertNull(events[2].name)
    }

    @Test
    fun `跨 chunk 边界分块喂入与一次性喂入结果一致`() {
        val raw = "event: message\ndata: {\"a\":1}\n\ndata: {\"b\":2}\n\n"

        val wholeParser = SseParser()
        val whole = wholeParser.onChunk(raw) + wholeParser.finish()

        val chunkParser = SseParser()
        val chunked = mutableListOf<SseEvent>()
        // 每次只喂 3 个字符，强制切在字段中间
        raw.chunked(3).forEach { chunked += chunkParser.onChunk(it) }
        chunked += chunkParser.finish()

        assertEquals(2, whole.size)
        assertEquals(2, chunked.size)
        assertEquals(whole.map { it.name }, chunked.map { it.name })
        assertEquals(whole.map { it.data.trim() }, chunked.map { it.data.trim() })
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), chunked.map { it.data.trim() })
    }

    @Test
    fun `以冒号开头的注释行被忽略`() {
        val parser = SseParser()
        val events = parser.onChunk(": keep-alive\n\n")
        assertTrue("心跳注释不应产生事件", events.isEmpty())

        val parser2 = SseParser()
        val events2 = parser2.onChunk(": keep-alive\ndata: {\"a\":1}\n\n")
        assertEquals(1, events2.size)
        assertEquals("{\"a\":1}", events2[0].data.trim())
    }

    @Test
    fun `finish 吐出末尾没有空行结束的数据`() {
        val parser = SseParser()
        val before = parser.onChunk("data: 最后一条")
        assertTrue("未遇到空行前不应产出事件", before.isEmpty())

        val after = parser.finish()
        assertEquals(1, after.size)
        assertEquals("最后一条", after[0].data)
    }

    @Test
    fun `finish 之后不会重复吐出已经结束的事件`() {
        val parser = SseParser()
        parser.onChunk("data: 第一条\n\n")
        val tail = parser.finish()
        assertTrue("已用空行结束的事件不应被 finish 重复吐出", tail.isEmpty())
    }

    @Test
    fun `多个 data 行会被拼成同一事件`() {
        val parser = SseParser()
        val events = parser.onChunk("data: 第一行\ndata: 第二行\n\n")
        assertEquals(1, events.size)
        assertEquals("第一行\n第二行", events[0].data.trim())
    }
}
