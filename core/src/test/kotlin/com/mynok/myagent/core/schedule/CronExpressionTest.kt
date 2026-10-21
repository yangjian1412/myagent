package com.mynok.myagent.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CronExpressionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): LocalDateTime = LocalDateTime.parse(text)

    @Test
    fun `daily at fixed time`() {
        val cron = CronExpression.parse("30 8 * * *")
        val next = cron.next(at("2026-10-21T07:00"), zone)
        assertEquals(at("2026-10-21T08:30"), next!!.toLocalDateTime())
    }

    @Test
    fun `next occurrence rolls to tomorrow when time already passed`() {
        val cron = CronExpression.parse("30 8 * * *")
        val next = cron.next(at("2026-10-21T09:00"), zone)
        assertEquals(at("2026-10-22T08:30"), next!!.toLocalDateTime())
    }

    @Test
    fun `strictly after from - same minute is not returned`() {
        val cron = CronExpression.parse("0 8 * * *")
        val next = cron.next(at("2026-10-21T08:00:00"), zone)
        assertEquals(at("2026-10-22T08:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `step values`() {
        val cron = CronExpression.parse("*/15 * * * *")
        val next = cron.next(at("2026-10-21T08:05"), zone)
        assertEquals(at("2026-10-21T08:15"), next!!.toLocalDateTime())
    }

    @Test
    fun `range values`() {
        val cron = CronExpression.parse("0 9-17 * * *")
        val next = cron.next(at("2026-10-21T08:30"), zone)
        assertEquals(at("2026-10-21T09:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `list values`() {
        val cron = CronExpression.parse("0 8,12,18 * * *")
        val next = cron.next(at("2026-10-21T09:00"), zone)
        assertEquals(at("2026-10-21T12:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `weekly on monday`() {
        // 2026-10-21 是周三
        val cron = CronExpression.parse("0 9 * * 1")
        val next = cron.next(at("2026-10-21T10:00"), zone)
        assertEquals(at("2026-10-26T09:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `sunday can be written as zero or seven`() {
        val a = CronExpression.parse("0 9 * * 0")
        val b = CronExpression.parse("0 9 * * 7")
        val from = at("2026-10-21T10:00")
        assertEquals(a.next(from, zone), b.next(from, zone))
    }

    @Test
    fun `named months and weekdays are accepted`() {
        // 「1 月的周一」：月份限定为 jan，星期限定为 mon
        val cron = CronExpression.parse("0 9 * jan mon")
        val next = cron.next(at("2026-10-21T10:00"), zone)
        assertEquals(at("2027-01-04T09:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `month restriction is honoured`() {
        // 只在 3 月触发
        val cron = CronExpression.parse("0 9 * 3 *")
        val next = cron.next(at("2026-10-21T10:00"), zone)
        assertEquals(at("2027-03-01T09:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `day of month and day of week are unioned when both restricted`() {
        // 每月 1 号，或者每周一
        val cron = CronExpression.parse("0 0 1 * 1")
        val next = cron.next(at("2026-10-21T10:00"), zone)
        // 下一个周一是 10-26
        assertEquals(at("2026-10-26T00:00"), next!!.toLocalDateTime())
    }

    @Test
    fun `invalid field count is rejected`() {
        assertNull(CronExpression.parseOrNull("30 8 * *"))
        assertNull(CronExpression.parseOrNull(""))
    }

    @Test
    fun `out of range values are rejected`() {
        assertNull(CronExpression.parseOrNull("70 8 * * *"))
        assertNull(CronExpression.parseOrNull("0 25 * * *"))
        assertNull(CronExpression.parseOrNull("0 0 32 * *"))
    }

    @Test
    fun `bad step is rejected`() {
        assertNull(CronExpression.parseOrNull("*/0 * * * *"))
        assertNull(CronExpression.parseOrNull("*/x * * * *"))
    }

    @Test
    fun `impossible expression returns null instead of looping forever`() {
        // 2 月 30 日不存在
        val cron = CronExpression.parse("0 0 30 2 *")
        val next = cron.next(at("2026-10-21T10:00"), zone)
        assertNull(next)
    }

    @Test
    fun `build helper creates valid expressions`() {
        val daily = CronExpression.parse(
            CronExpression.build(TriggerType.DAILY, java.time.LocalTime.of(8, 30)),
        )
        assertEquals("30 08 * * *", daily.expression)

        val weekly = CronExpression.parse(
            CronExpression.build(
                TriggerType.WEEKLY,
                java.time.LocalTime.of(9, 0),
                weekdays = setOf(1, 3, 5),
            ),
        )
        assertEquals("00 09 * * 1,3,5", weekly.expression)

        val interval = CronExpression.parse(
            CronExpression.build(TriggerType.INTERVAL, java.time.LocalTime.MIDNIGHT, intervalMinutes = 30),
        )
        assertEquals("*/30 * * * *", interval.expression)
    }

    @Test
    fun `describe produces human readable text`() {
        assertTrue(CronExpression.parse("30 8 * * *").describe().contains("每天"))
        assertTrue(CronExpression.parse("0 9 * * 1").describe().contains("周一"))
    }

    @Test
    fun `next uses the given zone`() {
        val cron = CronExpression.parse("0 8 * * *")
        val utc = ZoneId.of("UTC")
        val shanghai = ZoneId.of("Asia/Shanghai")
        // 00:30 UTC 就是上海时间 08:30，所以 08:00 已经过了
        val fromUtc = at("2026-10-21T00:30").atZone(utc)
        val next = cron.next(fromUtc, shanghai)
        assertEquals(8, next!!.hour)
        assertEquals(0, next.minute)
        assertEquals(at("2026-10-22T08:00"), next.toLocalDateTime())
    }

    @Test
    fun `parse is reusable and stable`() {
        val cron = CronExpression.parse("0 8 * * *")
        assertNotNull(cron)
        assertEquals("0 8 * * *", cron.expression)
    }

    @Test
    fun `preset round trips for daily`() {
        val preset = CronExpression.parse("30 08 * * *").toPreset()
        assertNotNull(preset)
        assertEquals(TriggerType.DAILY, preset!!.type)
        assertEquals(8, preset.hour)
        assertEquals(30, preset.minute)
    }

    @Test
    fun `preset round trips for weekly`() {
        val preset = CronExpression.parse("00 09 * * 1,3,5").toPreset()
        assertNotNull(preset)
        assertEquals(TriggerType.WEEKLY, preset!!.type)
        assertEquals(setOf(1, 3, 5), preset.weekdays)
        assertEquals(9, preset.hour)
    }

    @Test
    fun `sunday is mapped to seven for the ui`() {
        val preset = CronExpression.parse("0 9 * * 0").toPreset()
        assertEquals(setOf(7), preset!!.weekdays)
    }

    @Test
    fun `preset round trips for minute interval`() {
        val preset = CronExpression.parse("*/30 * * * *").toPreset()
        assertNotNull(preset)
        assertEquals(TriggerType.INTERVAL, preset!!.type)
        assertEquals(30, preset.intervalMinutes)
    }

    @Test
    fun `preset round trips for hour interval`() {
        val preset = CronExpression.parse("0 */6 * * *").toPreset()
        assertNotNull(preset)
        assertEquals(TriggerType.INTERVAL, preset!!.type)
        assertEquals(360, preset.intervalMinutes)
    }

    @Test
    fun `preset is null for expressions the simple picker cannot express`() {
        // 一天两个时间点
        assertNull(CronExpression.parse("0 9,18 * * *").toPreset())
        // 限定月份
        assertNull(CronExpression.parse("0 9 * 3 *").toPreset())
        // 每月 1 号
        assertNull(CronExpression.parse("0 9 1 * *").toPreset())
    }

    @Test
    fun `build then preset returns the same values`() {
        listOf(
            TriggerType.DAILY,
            TriggerType.WEEKLY,
            TriggerType.INTERVAL,
        ).forEach { type ->
            val expr = CronExpression.build(
                type = type,
                time = java.time.LocalTime.of(7, 45),
                weekdays = setOf(2, 4, 6),
                intervalMinutes = 30,
            )
            val preset = CronExpression.parse(expr).toPreset()
            assertNotNull("$type 的表达式应该能反解：$expr", preset)
            when (type) {
                TriggerType.DAILY -> {
                    assertEquals(7, preset!!.hour)
                    assertEquals(45, preset.minute)
                }
                TriggerType.WEEKLY -> assertEquals(setOf(2, 4, 6), preset!!.weekdays)
                TriggerType.INTERVAL -> assertEquals(30, preset!!.intervalMinutes)
            }
        }
    }

    @Test
    fun `intervals that do not divide sixty are rejected`() {
        assertNull(
            runCatching {
                CronExpression.build(TriggerType.INTERVAL, java.time.LocalTime.MIDNIGHT, intervalMinutes = 45)
            }.getOrNull(),
        )
        // 整小时可以
        assertEquals(
            "0 */2 * * *",
            CronExpression.build(TriggerType.INTERVAL, java.time.LocalTime.MIDNIGHT, intervalMinutes = 120),
        )
        // 不是整小时就拒绝
        assertNull(
            runCatching {
                CronExpression.build(TriggerType.INTERVAL, java.time.LocalTime.MIDNIGHT, intervalMinutes = 90)
            }.getOrNull(),
        )
    }
}
