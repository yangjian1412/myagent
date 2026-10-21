package com.mynok.myagent.data.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.mynok.myagent.core.schedule.CronExpression
import com.mynok.myagent.data.db.ScheduledTaskEntity
import java.time.ZonedDateTime

/**
 * 定时任务的调度器。
 *
 * 用 AlarmManager 把 App 叫醒，唤醒后由 [TaskRunner] 执行任务内容。
 * 精确闹钟在 Android 12+ 需要用户授权，拿不到就退化成非精确闹钟
 * （可能晚几分钟，但不会完全不跑）。
 */
class TaskScheduler(
    private val context: Context,
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 是否可以设定精确闹钟。
     */
    fun canScheduleExact(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 跳转到系统设置让用户授予精确闹钟权限。
     */
    fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 计算任务的下次触发时间。
     */
    fun nextRunAt(task: ScheduledTaskEntity, from: ZonedDateTime = ZonedDateTime.now()): Long? =
        CronExpression.parseOrNull(task.cron)?.next(from)?.toInstant()?.toEpochMilli()

    /**
     * 给任务排下一次闹钟。
     */
    fun schedule(task: ScheduledTaskEntity): Long? {
        val manager = alarmManager ?: return null
        val triggerAt = nextRunAt(task) ?: return null
        val pendingIntent = pendingIntent(task.id, mutable = false)
        val exact = canScheduleExact()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                // 没有精确闹钟权限时用非精确的，至少还能跑
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }.onFailure { return null }
        return triggerAt
    }

    /**
     * 取消任务的闹钟。
     */
    fun cancel(taskId: String) {
        val manager = alarmManager ?: return
        runCatching {
            manager.cancel(pendingIntent(taskId, mutable = false))
        }
    }

    /**
     * 重新排所有启用的任务（改完配置、开机、App 启动时用）。
     */
    fun rescheduleAll(tasks: List<ScheduledTaskEntity>) {
        tasks.forEach { task ->
            if (task.enabled) {
                schedule(task)
            } else {
                cancel(task.id)
            }
        }
    }

    private fun pendingIntent(taskId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java)
            .setAction(TaskAlarmReceiver.ACTION_RUN_TASK)
            .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId)
        val flags = if (mutable) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
