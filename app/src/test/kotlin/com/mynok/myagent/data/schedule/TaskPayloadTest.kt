package com.mynok.myagent.data.schedule

import com.mynok.myagent.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务 payload 的结构约定。
 *
 * payload 是 JSON 字符串，结构随 actionType 变化，这里固定住格式，
 * 免得 UI 写的时候和执行的时候对不上。
 */
class TaskPayloadTest {

    private fun parse(raw: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(raw).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun `notify payload carries title and body`() {
        val payload = parse("""{"title":"喝水","body":"{date} 记得喝水"}""")
        assertEquals("喝水", payload["title"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(payload.containsKey("body"))
    }

    @Test
    fun `tool payload carries tool name and args`() {
        val payload = parse("""{"tool":"calendar","args":{"action":"list_events"}}""")
        assertEquals("calendar", payload["tool"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(payload.containsKey("args"))
    }

    @Test
    fun `agent payload carries prompt`() {
        val payload = parse("""{"prompt":"总结今天的日程"}""")
        assertEquals(
            "总结今天的日程",
            payload["prompt"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    @Test
    fun `action types are the three we support`() {
        assertEquals("NOTIFY", ScheduledTaskEntity.ACTION_NOTIFY)
        assertEquals("TOOL", ScheduledTaskEntity.ACTION_TOOL)
        assertEquals("AGENT", ScheduledTaskEntity.ACTION_AGENT)
    }
}
