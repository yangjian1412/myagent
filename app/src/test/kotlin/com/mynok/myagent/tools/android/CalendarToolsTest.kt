package com.mynok.myagent.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarToolsTest {

    @Test
    fun `epoch millis is kept as is`() {
        assertEquals(1_700_000_000_000L, CalendarTools.parseTime("1700000000000"))
    }

    @Test
    fun `short epoch is treated as seconds`() {
        assertEquals(1_700_000_000_000L, CalendarTools.parseTime("1700000000"))
    }

    @Test
    fun `iso instant is parsed`() {
        assertEquals(
            Instant.parse("2026-10-21T06:00:00Z").toEpochMilli(),
            CalendarTools.parseTime("2026-10-21T06:00:00Z"),
        )
    }

    @Test
    fun `offset date time is parsed`() {
        assertEquals(
            Instant.parse("2026-10-21T06:00:00Z").toEpochMilli(),
            CalendarTools.parseTime("2026-10-21T14:00:00+08:00"),
        )
    }

    @Test
    fun `local date time uses system zone`() {
        val millis = CalendarTools.parseTime("2026-10-21 14:00")
            ?: error("应该能解析本地时间")
        val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        assertEquals(2026, local.year)
        assertEquals(10, local.monthValue)
        assertEquals(21, local.dayOfMonth)
        assertEquals(14, local.hour)
        assertEquals(0, local.minute)
    }

    @Test
    fun `date only starts at local midnight`() {
        val millis = CalendarTools.parseTime("2026-10-21")
            ?: error("应该能解析日期")
        val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        assertEquals(0, local.hour)
        assertEquals(0, local.minute)
        assertEquals(21, local.dayOfMonth)
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(CalendarTools.parseTime("明天下午"))
        assertNull(CalendarTools.parseTime(""))
        assertNull(CalendarTools.parseTime(null))
    }
}
