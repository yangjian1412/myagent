package com.mynok.myagent.tools.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.boolArg
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.math.abs

/**
 * 日历：查询、创建、修改、删除。
 *
 * 查询/写入走 CalendarProvider；没有写权限时，创建操作会降级成系统日历的「预填新建」页面。
 */
object CalendarTools {

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val slashDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun calendar(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "calendar",
            description = "手机日历。action=list_calendars / list_events / create / update / delete。" +
                "begin、end 支持 2026-10-21 14:00、2026-10-21T14:00:00+08:00 或 epoch 毫秒。" +
                "list_events 默认查询未来 7 天；create 不传 calendar_id 时用主日历；" +
                "没有日历权限时 create 会打开系统日历的预填页面。create / update / delete 写操作会要求确认。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("list_calendars"))
                            add(JsonPrimitive("list_events"))
                            add(JsonPrimitive("create"))
                            add(JsonPrimitive("update"))
                            add(JsonPrimitive("delete"))
                        })
                    })
                    put("event_id", buildJsonObject {
                        put("type", "string")
                        put("description", "update / delete 需要")
                    })
                    put("calendar_id", buildJsonObject {
                        put("type", "string")
                        put("description", "目标日历 id；省略时用主日历")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "日程标题")
                    })
                    put("begin", buildJsonObject {
                        put("type", "string")
                        put("description", "开始时间")
                    })
                    put("end", buildJsonObject {
                        put("type", "string")
                        put("description", "结束时间；省略时默认 1 小时")
                    })
                    put("all_day", buildJsonObject {
                        put("type", "boolean")
                        put("description", "是否全天日程")
                    })
                    put("location", buildJsonObject { put("type", "string") })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "日程备注")
                    })
                    put("reminder_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "提前多少分钟提醒；省略表示不额外添加提醒")
                    })
                    put("timezone", buildJsonObject {
                        put("type", "string")
                        put("description", "时区 id，例如 Asia/Shanghai；默认系统时区")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "list_events 最多返回多少条，默认 30，最大 100")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val action = args.stringArg("action")?.lowercase() ?: "list_events"
                if (action in WRITE_ACTIONS) {
                    val preflight = when (action) {
                        "create" -> when {
                            args.stringArg("title", "name") == null -> "创建日程需要 title 参数"
                            parseTime(args.stringArg("begin", "start", "from")) == null ->
                                "创建日程需要 begin 参数（例如 2026-10-21 14:00）"
                            else -> null
                        }
                        else -> if (args.stringArg("event_id", "id")?.toLongOrNull() == null) {
                            "$action 需要 event_id"
                        } else {
                            null
                        }
                    }
                    if (preflight != null) {
                        return@withContext ToolResult.error(preflight)
                    }
                    val approved = ctx.approver.request(
                        ToolApproval(
                            toolName = spec.name,
                            reason = when (action) {
                                "create" -> "在日历中新建「${args.stringArg("title").orEmpty()}」"
                                "update" -> "修改日历事件 id=${args.stringArg("event_id").orEmpty()}"
                                else -> "删除日历事件 id=${args.stringArg("event_id").orEmpty()}"
                            },
                            risk = if (action == "delete") {
                                ToolRisk.DESTRUCTIVE
                            } else {
                                ToolRisk.WRITE
                            },
                            detail = args.toString().take(500),
                        ),
                    )
                    if (!approved) {
                        return@withContext ToolResult.error("用户拒绝执行日历写操作（calendar $action）。")
                    }
                }
                try {
                    when (action) {
                        "list_calendars" -> listCalendars(context)
                        "list_events" -> listEvents(context, args)
                        "create" -> createEvent(context, args)
                        "update" -> updateEvent(context, args)
                        "delete" -> deleteEvent(context, args)
                        else -> ToolResult.error("不支持的 action：$action")
                    }
                } catch (e: SecurityException) {
                    ToolResult.error(
                        "日历权限不足。请让用户打开「模型 → 工具 → 系统权限」，授予日历权限后重试。" +
                            "（${e.message ?: "SecurityException"}）",
                    )
                } catch (e: Exception) {
                    ToolResult.error("日历操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    private val WRITE_ACTIONS = setOf("create", "update", "delete")

    // ------------------------------------------------------------ 查询

    private fun listCalendars(context: Context): ToolResult {
        if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) {
            return ToolResult.error(permissionHint("日历"))
        }
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_COLOR,
            Calendars.IS_PRIMARY,
            Calendars.VISIBLE,
            Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${Calendars.IS_PRIMARY} DESC, ${Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "未命名日历"
                val account = cursor.getString(2) ?: ""
                val owner = cursor.getString(3) ?: ""
                val color = cursor.getInt(4)
                val primary = cursor.getInt(5) == 1
                val visible = cursor.getInt(6) == 1
                val access = cursor.getInt(7)
                val writable = access >= Calendars.CAL_ACCESS_CONTRIBUTOR
                rows += buildString {
                    append("- id=$id「$name」")
                    if (primary) append(" [主日历]")
                    if (!visible) append(" [隐藏]")
                    if (!writable) append(" [只读]")
                    if (account.isNotBlank()) append(" 账户=$account")
                    if (owner.isNotBlank() && owner != account) append(" owner=$owner")
                    append(" 颜色=#%06X".format(color and 0xFFFFFF))
                }
            }
        }
        if (rows.isEmpty()) return ToolResult.ok("没有找到任何日历。")
        return ToolResult.ok("共 ${rows.size} 个日历：\n" + rows.joinToString("\n"))
    }

    private fun listEvents(context: Context, args: JsonObject): ToolResult {
        if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) {
            return ToolResult.error(permissionHint("日历"))
        }
        val now = System.currentTimeMillis()
        val begin = parseTime(args.stringArg("begin", "start", "from")) ?: now
        val end = parseTime(args.stringArg("end", "to"))
            ?: (begin + 7L * 24 * 60 * 60 * 1000)
        if (end <= begin) return ToolResult.error("end 必须晚于 begin")

        val calendarId = parseCalendarId(args)
        val limit = (args.intArg("limit") ?: 30).coerceIn(1, 100)

        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.EVENT_LOCATION,
            Instances.CALENDAR_ID,
        )
        val selection = calendarId?.let { "${Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }
        val names = calendarNames(context)
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            builder.build(),
            projection,
            selection,
            selectionArgs,
            "${Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val eventId = cursor.getLong(0)
                val title = cursor.getString(1) ?: "（无标题）"
                val start = cursor.getLong(2)
                val finish = cursor.getLong(3)
                val allDay = cursor.getInt(4) == 1
                val location = cursor.getString(5)
                val calId = cursor.getLong(6)
                rows += buildString {
                    append("- [id=$eventId] $title")
                    append("  ${formatRange(start, finish, allDay)}")
                    if (!location.isNullOrBlank()) append("  @$location")
                    names[calId]?.let { append("  （$it）") }
                }
            }
        }
        val range = "${formatDateTime(begin)} → ${formatDateTime(end)}"
        if (rows.isEmpty()) return ToolResult.ok("$range 没有日程。")
        return ToolResult.ok("$range 共 ${rows.size} 个日程：\n" + rows.joinToString("\n"))
    }

    private fun calendarNames(context: Context): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getLong(0)] = cursor.getString(1) ?: "日历"
            }
        }
        return result
    }

    // ------------------------------------------------------------ 创建

    private fun createEvent(context: Context, args: JsonObject): ToolResult {
        val title = args.stringArg("title", "name")
            ?: return ToolResult.error("创建日程需要 title 参数")
        val beginRaw = parseTime(args.stringArg("begin", "start", "from"))
            ?: return ToolResult.error("创建日程需要 begin 参数（例如 2026-10-21 14:00）")
        val endRaw = parseTime(args.stringArg("end", "to"))
        val allDay = args.boolArg("all_day", default = false)
        val (begin, end) = normalizeRange(beginRaw, endRaw, allDay)
            ?: return ToolResult.error("结束时间必须晚于开始时间")
        val location = args.stringArg("location", "place")
        val description = args.stringArg("description", "note", "notes")
        val timezone = if (allDay) {
            "UTC"
        } else {
            args.stringArg("timezone", "tz") ?: TimeZone.getDefault().id
        }

        // 没有写权限：降级为系统日历的预填新建页面，用户自己点保存
        if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(Events.CONTENT_URI)
                .putExtra(Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(Events.ALL_DAY, allDay)
                .putExtra(Events.EVENT_TIMEZONE, timezone)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            location?.let { intent.putExtra(Events.EVENT_LOCATION, it) }
            description?.let { intent.putExtra(Events.DESCRIPTION, it) }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error(permissionHint("日历") + "\n并且没有找到可用的日历应用。")
            }
            context.startActivity(intent)
            return ToolResult.ok(
                "没有日历写入权限，已打开系统日历的预填页面：" +
                    "「$title」${formatRange(begin, end, allDay)}。请用户确认保存。\n" +
                    permissionHint("日历"),
            )
        }

        val calendarId = pickCalendarId(context, parseCalendarId(args))
            ?: return ToolResult.error("没有找到可写的日历，请先在系统日历里添加一个账户。")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, begin)
            put(Events.DTEND, end)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, timezone)
            put(Events.EVENT_END_TIMEZONE, timezone)
            location?.let { put(Events.EVENT_LOCATION, it) }
            description?.let { put(Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            ?: return ToolResult.error("写入日历失败：ContentProvider 没有返回新事件 id。")
        val eventId = ContentUris.parseId(uri)

        val reminder = args.intArg("reminder_minutes")
        val reminderNote = if (reminder != null && reminder >= 0) {
            addReminder(context, eventId, reminder)
        } else {
            null
        }

        return ToolResult.ok(
            buildString {
                append("已创建日程 [id=$eventId]「$title」${formatRange(begin, end, allDay)}。")
                if (!location.isNullOrBlank()) append(" 地点：$location")
                reminderNote?.let { append("\n$it") }
            },
        )
    }

    private fun addReminder(context: Context, eventId: Long, minutes: Int): String? {
        return runCatching {
            context.contentResolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, minutes)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
            "已设置提前 $minutes 分钟提醒。"
        }.getOrElse { "日程已创建，但提醒添加失败：${it.message ?: it.javaClass.simpleName}" }
    }

    // ------------------------------------------------------------ 修改

    private fun updateEvent(context: Context, args: JsonObject): ToolResult {
        if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return ToolResult.error(permissionHint("日历"))
        }
        val eventId = args.stringArg("event_id", "id")?.toLongOrNull()
            ?: return ToolResult.error("update 需要 event_id")
        val existing = readEvent(context, eventId)
            ?: return ToolResult.error("没有找到 id=$eventId 的日程（可能已删除）。")

        val allDay = if (args.containsKey("all_day")) {
            args.boolArg("all_day")
        } else {
            existing.allDay
        }
        val beginArg = parseTime(args.stringArg("begin", "start", "from"))
        val endArg = parseTime(args.stringArg("end", "to"))
        val turningAllDay = args.containsKey("all_day") && allDay != existing.allDay
        val begin = when {
            beginArg != null -> if (allDay) allDayStart(beginArg) else beginArg
            turningAllDay && allDay -> allDayStart(existing.begin)
            else -> existing.begin
        }
        val end = when {
            endArg != null -> if (allDay) allDayStart(endArg) else endArg
            turningAllDay && allDay -> begin + DAY_MS
            turningAllDay -> begin + (existing.end - existing.begin).coerceAtLeast(60_000L)
            beginArg != null && allDay -> begin + DAY_MS
            beginArg != null -> begin + (existing.end - existing.begin).coerceAtLeast(60_000L)
            else -> existing.end
        }
        if (end <= begin) return ToolResult.error("结束时间必须晚于开始时间")

        val values = ContentValues()
        args.stringArg("title", "name")?.let { values.put(Events.TITLE, it) }
        args.stringArg("location", "place")?.let { values.put(Events.EVENT_LOCATION, it) }
        args.stringArg("description", "note", "notes")?.let { values.put(Events.DESCRIPTION, it) }
        if (args.containsKey("all_day") || beginArg != null || endArg != null || existing.allDay) {
            values.put(Events.DTSTART, begin)
            values.put(Events.DTEND, end)
            values.put(Events.ALL_DAY, if (allDay) 1 else 0)
            val timezone = if (allDay) {
                "UTC"
            } else {
                args.stringArg("timezone", "tz") ?: TimeZone.getDefault().id
            }
            values.put(Events.EVENT_TIMEZONE, timezone)
            values.put(Events.EVENT_END_TIMEZONE, timezone)
        }

        val updated = context.contentResolver.update(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            values,
            null,
            null,
        )
        if (updated == 0) return ToolResult.error("没有更新任何日程，请确认 event_id 是否正确。")

        val reminder = args.intArg("reminder_minutes")
        val reminderNote = if (reminder != null) {
            runCatching {
                context.contentResolver.delete(
                    Reminders.CONTENT_URI,
                    "${Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString()),
                )
                if (reminder >= 0) addReminder(context, eventId, reminder) else null
            }.getOrElse { "提醒更新失败：${it.message ?: it.javaClass.simpleName}" }
        } else {
            null
        }

        return ToolResult.ok(
            buildString {
                append("已更新日程 [id=$eventId]。")
                if (args.stringArg("title", "name") != null) {
                    append(" 标题：${args.stringArg("title", "name")}")
                }
                if (beginArg != null || endArg != null || args.containsKey("all_day")) {
                    append(" 新时间：${formatRange(begin, end, allDay)}")
                }
                reminderNote?.let { append("\n$it") }
            },
        )
    }

    // ------------------------------------------------------------ 删除

    private fun deleteEvent(context: Context, args: JsonObject): ToolResult {
        if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return ToolResult.error(permissionHint("日历"))
        }
        val eventId = args.stringArg("event_id", "id")?.toLongOrNull()
            ?: return ToolResult.error("delete 需要 event_id")
        val existing = readEvent(context, eventId)
            ?: return ToolResult.error("没有找到 id=$eventId 的日程（可能已删除）。")
        val deleted = context.contentResolver.delete(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            null,
            null,
        )
        if (deleted == 0) return ToolResult.error("删除失败：没有匹配到日程。")
        return ToolResult.ok(
            "已删除日程 [id=$eventId]「${existing.title}」。" +
                "如果是重复日程，会删除整个系列。",
        )
    }

    // ------------------------------------------------------------ 工具函数

    private data class EventInfo(
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
    )

    private fun readEvent(context: Context, eventId: Long): EventInfo? {
        val projection = arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY)
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return EventInfo(
                    title = cursor.getString(0) ?: "（无标题）",
                    begin = cursor.getLong(1),
                    end = cursor.getLong(2),
                    allDay = cursor.getInt(3) == 1,
                )
            }
        }
        return null
    }

    private fun pickCalendarId(context: Context, requested: Long?): Long? {
        if (requested != null) return requested
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.VISIBLE,
        )
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${Calendars.IS_PRIMARY} DESC, ${Calendars.VISIBLE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val access = cursor.getInt(1)
                val visible = cursor.getInt(3) == 1
                if (visible && access >= Calendars.CAL_ACCESS_CONTRIBUTOR) return id
            }
        }
        return null
    }

    private fun parseCalendarId(args: JsonObject): Long? =
        args.stringArg("calendar_id", "calendar")?.toLongOrNull()

    internal fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        text.toLongOrNull()?.let { value ->
            return if (abs(value) < 10_000_000_000L) value * 1000 else value
        }
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
        runCatching { ZonedDateTime.parse(text) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
        runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { LocalDateTime.parse(text, dateTimeFormatter) }.getOrNull()
            ?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { LocalDateTime.parse(text, slashDateTimeFormatter) }.getOrNull()
            ?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(text) }.getOrNull()
            ?.let { return it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(text, dateFormatter) }.getOrNull()
            ?.let { return it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return null
    }

    /** 全天的 DTSTART/DTEND 用 UTC 零点，和系统日历保持一致。 */
    private fun allDayStart(ms: Long): Long =
        Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private fun normalizeRange(begin: Long, end: Long?, allDay: Boolean): Pair<Long, Long>? {
        val start = if (allDay) allDayStart(begin) else begin
        val finish = when {
            end != null && allDay -> allDayStart(end)
            end != null -> end
            allDay -> start + DAY_MS
            else -> start + 60L * 60 * 1000
        }
        return if (finish > start) start to finish else null
    }

    private fun formatRange(begin: Long, end: Long, allDay: Boolean): String =
        if (allDay) {
            val startDate = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
            "$startDate 全天（至 $endDate）"
        } else {
            "${formatDateTime(begin)}–${formatDateTime(end)}"
        }

    private fun formatDateTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
