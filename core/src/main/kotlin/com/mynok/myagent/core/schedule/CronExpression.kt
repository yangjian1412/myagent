package com.mynok.myagent.core.schedule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * cron 表达式。
 *
 * 用 5 段 Unix 格式：分 时 日 月 周。
 * - `*` 任意
 * - `a-b` 闭区间
 * - `a,b,c` 多个值
 * - `*\/n` 或 `a-b/n` 步长
 * - 月份 1-12（也接受 jan-dec），星期 0-6（0 和 7 都是周日，也接受 sun-sat）
 *
 * 只负责"算下一次什么时候触发"，不负责唤醒——那是 Android 侧的 AlarmManager。
 */
class CronExpression private constructor(
    val expression: String,
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    /** 星期集合，0 = 周日。 */
    val daysOfWeek: Set<Int>,
    /** 日与周同时被限定时的取交集还是取并集。标准 cron 是并集（OR）。 */
    private val dayUnion: Boolean,
) {

    /** 这个表达式能不能用 UI 的简易选择器完整表达出来。 */
    fun toPreset(): CronPreset? = PresetParser.presetOf(this)

    /**
     * 计算严格晚于 [from] 的下一次触发时间。
     *
     * @param from 起点（不含它本身）
     * @param zone 时区
     * @param limitYears 最多往后找多少年，避免非法表达式死循环
     * @return 下一次触发时间；找不到返回 null
     */
    fun next(from: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? =
        next(from.atZone(zone), zone)

    /**
     * 计算严格晚于 [from] 的下一次触发时间。
     *
     * @param from 起点（不含它本身）
     * @param zone 时区
     * @param limitYears 最多往后找多少年，避免非法表达式死循环
     * @return 下一次触发时间；找不到返回 null
     */
    fun next(from: ZonedDateTime, zone: ZoneId = from.zone, limitYears: Int = 5): ZonedDateTime? {
        val start = from.withZoneSameInstant(zone).withNano(0)
        // 从下一分钟开始找
        var candidate = start.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusMinutes(1)

        val limit = start.plusYears(limitYears.toLong())
        while (candidate.isBefore(limit)) {
            if (candidate.monthValue !in months) {
                // 跳到下个月 1 号 00:00
                candidate = candidate.withDayOfMonth(1).toLocalDate().atStartOfDay(zone)
                    .let { it.plusMonths(1) }
                continue
            }
            if (!matchesDay(candidate)) {
                candidate = candidate.toLocalDate().plusDays(1).atStartOfDay(zone)
                continue
            }
            if (candidate.hour !in hours) {
                candidate = candidate.toLocalDate().atTime(candidate.hour, 0)
                    .atZone(zone)
                    .plusHours(1)
                continue
            }
            if (candidate.minute !in minutes) {
                candidate = candidate.plusMinutes(1)
                continue
            }
            return candidate
        }
        return null
    }

    /** 日与周的匹配：两者都被限定时取并集（标准 cron 行为）。 */
    private fun matchesDay(time: ZonedDateTime): Boolean {
        val domRestricted = daysOfMonth.isNotEmpty() && daysOfMonth != ALL_DAYS_OF_MONTH
        val dowRestricted = daysOfWeek.isNotEmpty() && daysOfWeek != ALL_DAYS_OF_WEEK
        val domMatch = time.dayOfMonth in daysOfMonth
        val dowMatch = time.dayOfWeek.toCronNumber() in daysOfWeek
        return when {
            domRestricted && dowRestricted -> if (dayUnion) domMatch || dowMatch else domMatch && dowMatch
            domRestricted -> domMatch
            dowRestricted -> dowMatch
            else -> true
        }
    }

    private fun DayOfWeek.toCronNumber(): Int = value % 7

    /** 人话描述，给 UI 用。 */
    fun describe(): String {
        val timeText = if (minutes.size == 1 && hours.size == 1) {
            String.format("%02d:%02d", hours.first(), minutes.first())
        } else {
            "${minutes.size} 个分钟点 × ${hours.size} 个小时"
        }
        val dayText = when {
            daysOfMonth == ALL_DAYS_OF_MONTH && daysOfWeek == ALL_DAYS_OF_WEEK -> "每天"
            daysOfMonth == ALL_DAYS_OF_MONTH && daysOfWeek.isNotEmpty() ->
                daysOfWeek.sorted().joinToString("、") { weekdayLabel(it) }
            daysOfWeek == ALL_DAYS_OF_WEEK && daysOfMonth.isNotEmpty() ->
                "每月 ${daysOfMonth.sorted().joinToString("、")} 日"
            else -> "自定义"
        }
        return "$dayText $timeText"
    }

    private fun weekdayLabel(cron: Int): String = when (cron) {
        0 -> "周日"
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周?"
    }

    companion object {
        private val ALL_DAYS_OF_MONTH = (1..31).toSet()
        private val ALL_DAYS_OF_WEEK = (0..6).toSet()

        private val MONTH_NAMES = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )

        private val DAY_NAMES = mapOf(
            "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6,
        )

        /**
         * 解析 cron 表达式，失败抛 [IllegalArgumentException]。
         */
        fun parse(expression: String): CronExpression {
            val trimmed = expression.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("cron 表达式不能为空")
            val fields = trimmed.split(Regex("\\s+"))
            if (fields.size != 5) {
                throw IllegalArgumentException(
                    "cron 表达式需要 5 段（分 时 日 月 周），实际 ${fields.size} 段：$expression",
                )
            }
            val minutes = parseField(fields[0], 0, 59, emptyMap())
            val hours = parseField(fields[1], 0, 23, emptyMap())
            val daysOfMonth = parseField(fields[2], 1, 31, emptyMap())
            val months = parseField(fields[3], 1, 12, MONTH_NAMES)
            val daysOfWeek = parseField(fields[4], 0, 7, DAY_NAMES).map { it % 7 }.toSet()

            // 日与周都被限定时，标准 cron 取并集
            val dayUnion = daysOfMonth != ALL_DAYS_OF_MONTH && daysOfWeek != ALL_DAYS_OF_WEEK
            return CronExpression(
                expression = trimmed,
                minutes = minutes,
                hours = hours,
                daysOfMonth = daysOfMonth,
                months = months,
                daysOfWeek = daysOfWeek,
                dayUnion = dayUnion,
            )
        }

        /** 解析失败返回 null 的版本。 */
        fun parseOrNull(expression: String): CronExpression? =
            runCatching { parse(expression) }.getOrNull()

        private fun parseField(
            field: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
        ): Set<Int> {
            val result = mutableSetOf<Int>()
            for (part in field.split(',')) {
                val piece = part.trim().lowercase()
                if (piece.isEmpty()) throw IllegalArgumentException("空的 cron 字段片段")
                val (rangePart, step) = if (piece.contains('/')) {
                    val slash = piece.split('/')
                    if (slash.size != 2) throw IllegalArgumentException("非法的步长写法：$part")
                    val stepValue = slash[1].toIntOrNull()
                        ?: throw IllegalArgumentException("非法的步长：$part")
                    if (stepValue <= 0) throw IllegalArgumentException("步长必须大于 0：$part")
                    slash[0] to stepValue
                } else {
                    piece to 1
                }
                val (from, to) = parseRange(rangePart, min, max, names)
                var value = from
                while (value <= to) {
                    result += value
                    value += step
                }
            }
            return result
        }

        private fun parseRange(
            part: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
        ): Pair<Int, Int> {
            fun resolve(raw: String): Int {
                val nameValue = names[raw]
                if (nameValue != null) return nameValue
                val value = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("非法的 cron 字段值：$raw")
                return value
            }

            return if (part == "*") {
                min to max
            } else if (part.contains('-')) {
                val pieces = part.split('-')
                if (pieces.size != 2) throw IllegalArgumentException("非法的区间：$part")
                val from = resolve(pieces[0].trim())
                val to = resolve(pieces[1].trim())
                if (from > to) throw IllegalArgumentException("区间起点大于终点：$part")
                if (from < min || to > max) {
                    throw IllegalArgumentException("区间超出范围 $min-$max：$part")
                }
                from to to
            } else {
                val value = resolve(part)
                if (value < min || value > max) {
                    throw IllegalArgumentException("值超出范围 $min-$max：$part")
                }
                value to value
            }
        }

        /**
         * 从 UI 的选择器生成表达式。
         *
         * @param type 触发类型
         * @param time 每天触发的时间点
         * @param weekdays 星期几（1=周一 … 7=周日），空表示每天
         * @param intervalMinutes 间隔模式的分钟数
         */
        fun build(
            type: TriggerType,
            time: LocalTime,
            weekdays: Set<Int> = emptySet(),
            intervalMinutes: Int = 60,
        ): String = when (type) {
            TriggerType.DAILY -> "%02d %02d * * *".format(time.minute, time.hour)
            TriggerType.WEEKLY -> {
                val days = weekdays.ifEmpty { setOf(1) }
                    .joinToString(",") { it.toString() }
                "%02d %02d * * %s".format(time.minute, time.hour, days)
            }
            TriggerType.INTERVAL -> {
                if (intervalMinutes < 1) throw IllegalArgumentException("间隔必须大于 0 分钟")
                if (intervalMinutes < 60) {
                    // cron 的 */n 只在 n 整除 60 时才是「真的每 n 分钟」
                    if (60 % intervalMinutes != 0) {
                        throw IllegalArgumentException(
                            "分钟间隔必须能整除 60（1/2/3/4/5/6/10/12/15/20/30），" +
                                "或者用整小时",
                        )
                    }
                    "*/$intervalMinutes * * * *"
                } else {
                    if (intervalMinutes % 60 != 0) {
                        throw IllegalArgumentException("超过一小时的间隔必须是整小时")
                    }
                    val hours = intervalMinutes / 60
                    if (hours > 23) {
                        throw IllegalArgumentException("小时间隔必须在 1-23 之间（一天最多 24 次）")
                    }
                    "0 */$hours * * *"
                }
            }
        }

        /** UI 上可选的分钟间隔。 */
        val minuteIntervalOptions: List<Int> = listOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30)

        /** UI 上可选的小时间隔。 */
        val hourIntervalOptions: List<Int> = listOf(1, 2, 3, 4, 6, 8, 12)
    }
}

/** 触发类型。 */
enum class TriggerType(val label: String) {
    DAILY("每天"),
    WEEKLY("每周"),
    INTERVAL("每隔一段时间"),
}

/**
 * 简易选择器能表达的调度规则。
 *
 * 用 UI 选出来的规则一定能用 [CronExpression.build] 还原成同一个表达式；
 * 反过来从表达式解析时，只有结构对得上才会返回非 null，
 * 避免把 `0 9,18 * * *` 这种选择器表达不了的东西硬塞进表单。
 */
data class CronPreset(
    val type: TriggerType,
    /** 每天 / 每周模式下的小时。 */
    val hour: Int = 8,
    /** 每天 / 每周模式下的分钟。 */
    val minute: Int = 0,
    /** 每周模式下的星期，1 = 周一 … 7 = 周日。 */
    val weekdays: Set<Int> = emptySet(),
    /** 间隔模式下的分钟数。 */
    val intervalMinutes: Int = 60,
)

/** 从解析好的 cron 表达式反推 UI 表单。 */
internal object PresetParser {

    fun presetOf(cron: CronExpression): CronPreset? {
        if (cron.minutes.size != 1) {
            // 只有「每 N 分钟」是分钟多值里能表达的
            val interval = intervalMinutesOf(cron) ?: return null
            return CronPreset(type = TriggerType.INTERVAL, intervalMinutes = interval)
        }
        val minute = cron.minutes.first()

        // 每小时的第 N 分，且小时是步长 → 每 N 小时
        if (cron.hours.size > 1) {
            val step = hourStepOf(cron) ?: return null
            return CronPreset(type = TriggerType.INTERVAL, intervalMinutes = step * 60)
        }

        val hour = cron.hours.firstOrNull() ?: return null
        if (cron.daysOfMonth != (1..31).toSet() || cron.months != (1..12).toSet()) return null

        return when (cron.daysOfWeek) {
            (0..6).toSet() -> CronPreset(TriggerType.DAILY, hour, minute)
            else -> CronPreset(
                type = TriggerType.WEEKLY,
                hour = hour,
                minute = minute,
                weekdays = cron.daysOfWeek.map { if (it == 0) 7 else it }.toSet(),
            )
        }
    }

    /** 每 N 分钟：分钟字段是步长，其余字段全放开。 */
    private fun intervalMinutesOf(cron: CronExpression): Int? {
        if (cron.hours != (0..23).toSet()) return null
        if (cron.daysOfMonth != (1..31).toSet()) return null
        if (cron.months != (1..12).toSet()) return null
        if (cron.daysOfWeek != (0..6).toSet()) return null
        val values = cron.minutes
        if (values.size < 2) return null
        val step = values.sorted().let { it[1] - it[0] }
        if (step <= 0) return null
        // 必须正好是 0, step, 2*step … 且能被 60 整除
        if (60 % step != 0) return null
        val expected = (0..59 step step).toSet()
        return if (values == expected) step else null
    }

    /** 每 N 小时：分钟固定 0，小时字段是步长。 */
    private fun hourStepOf(cron: CronExpression): Int? {
        if (cron.daysOfMonth != (1..31).toSet()) return null
        if (cron.months != (1..12).toSet()) return null
        if (cron.daysOfWeek != (0..6).toSet()) return null
        val values = cron.hours.sorted()
        val step = values.getOrNull(1)?.minus(values[0]) ?: return null
        if (step <= 0 || 24 % step != 0) return null
        val expected = (0..23 step step).toSet()
        return if (values.toSet() == expected) step else null
    }
}

/**
 * 下一次触发时间的便捷计算（本地时区）。
 */
fun CronExpression.nextLocal(
    from: LocalDateTime = LocalDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ZonedDateTime? = next(from.atZone(zone), zone)
