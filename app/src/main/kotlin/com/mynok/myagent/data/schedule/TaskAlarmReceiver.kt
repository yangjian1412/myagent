package com.mynok.myagent.data.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mynok.myagent.MyAgentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟接收器。
 *
 * 收到后不直接干活（BroadcastReceiver 只有几秒时间），
 * 而是转交 [TaskRunService] 这个前台服务去执行。
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_TASK) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.i(TAG, "定时任务触发：$taskId")
        TaskRunService.start(context, taskId)
    }

    companion object {
        const val ACTION_RUN_TASK = "com.mynok.myagent.task.RUN"
        const val EXTRA_TASK_ID = "task_id"
        private const val TAG = "TaskAlarmReceiver"
    }
}

/**
 * 开机后重排所有闹钟。
 *
 * 系统重启会清掉所有 AlarmManager 闹钟，所以要重新注册。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) {
            return
        }
        Log.i(TAG, "开机/更新完成，重排定时任务")
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (appContext as MyAgentApp).container
                val tasks = container.scheduledTaskRepository.getEnabled()
                container.taskScheduler.rescheduleAll(tasks)
            } catch (e: Exception) {
                Log.e(TAG, "重排定时任务失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
