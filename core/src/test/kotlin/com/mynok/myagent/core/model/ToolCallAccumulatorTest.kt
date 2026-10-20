package com.mynok.myagent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具调用分片累加器测试。 */
class ToolCallAccumulatorTest {

    @Test
    fun `多个 index 的分片分别累积成多个工具调用`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "call_a", name = "alpha", argumentsDelta = "{\"x\":"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 1, id = "call_b", name = "beta", argumentsDelta = "{\"y\":"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, argumentsDelta = "1}"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 1, argumentsDelta = "2}"))

        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("alpha", calls[0].name)
        assertEquals("call_a", calls[0].id)
        assertEquals("{\"x\":1}", calls[0].arguments)
        assertEquals("beta", calls[1].name)
        assertEquals("call_b", calls[1].id)
        assertEquals("{\"y\":2}", calls[1].arguments)
    }

    @Test
    fun `乱序到达的 index 也会按 index 排序输出`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 1, id = "call_b", name = "beta"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "call_a", name = "alpha"))

        val calls = acc.build()
        assertEquals(listOf("alpha", "beta"), calls.map { it.name })
    }

    @Test
    fun `id 与 name 只在首个分片给出时也能保留`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "call_1", name = "search", argumentsDelta = "{\"q\":"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, argumentsDelta = "\"猫\"}"))

        val call = acc.build().single()
        assertEquals("call_1", call.id)
        assertEquals("search", call.name)
        assertEquals("{\"q\":\"猫\"}", call.arguments)
        assertTrue(call.isComplete)
    }

    @Test
    fun `arguments 分片按顺序拼接`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "c", name = "n"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, argumentsDelta = "{\"a\""))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, argumentsDelta = ":1,"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, argumentsDelta = "\"b\":2}"))

        assertEquals("{\"a\":1,\"b\":2}", acc.build().single().arguments)
    }

    @Test
    fun `没有 arguments 时默认为空对象`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "call_1", name = "ping"))

        val call = acc.build().single()
        assertEquals("{}", call.arguments)
    }

    @Test
    fun `名字为空的分片被过滤掉`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, id = "call_0", name = "   ", argumentsDelta = "{}"))
        acc.onDelta(ModelEvent.ToolCallDelta(index = 1, id = "call_1", name = "real", argumentsDelta = "{}"))

        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("real", calls[0].name)
    }

    @Test
    fun `没有 id 时按 index 生成稳定的 id`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(ModelEvent.ToolCallDelta(index = 3, name = "tool", argumentsDelta = "{}"))

        val call = acc.build().single()
        assertEquals("call_3_0", call.id)
    }

    @Test
    fun `isEmpty 反映是否收到过分片`() {
        val acc = ToolCallAccumulator()
        assertTrue(acc.isEmpty())
        acc.onDelta(ModelEvent.ToolCallDelta(index = 0, name = "x"))
        assertFalse(acc.isEmpty())
    }
}
