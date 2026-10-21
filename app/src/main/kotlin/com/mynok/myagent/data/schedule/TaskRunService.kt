package com.mynok.myagent.data.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mynok.myagent.MainActivity
import com.mynok.myagent.MyAgentApp
import com.mynok.myagent.core.agent.AgentEvent
import com.mynok.myagent.core.context.ContextBudget
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.tools.ToolApprover
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.data.db.ScheduledTaskEntity
import com.mynok.myagent.data.repo.Mappers
import com.mynok.myagent.di.AndroidToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 执行定时任务的前台服务。
 *
 * 任务可能在后台跑几十秒（要调模型），超过 BroadcastReceiver 的时间限制，
 * 所以用前台服务顶着，执行完发一条通知。
 */
class TaskRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(TaskAlarmReceiver.EXTRA_TASK_ID)
        if (taskId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification())
        scope.launch {
            try {
                val container = (application as MyAgentApp).container
                TaskRunner(container).runTask(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "执行定时任务失败", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildRunningNotification(): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在执行定时任务")
            .setContentText("MyAgent 正在按计划运行任务")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TaskRunService"
        private const val CHANNEL_ID = "myagent_tasks"
        private const val NOTIFICATION_ID_RUNNING = 4301
        private const val NOTIFICATION_ID_RESULT = 4302

        fun start(context: Context, taskId: String) {
            val intent = Intent(context, TaskRunService::class.java)
                .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                Log.e(TAG, "启动任务服务失败", it)
            }
        }

        /** 建通知渠道。 */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定时任务",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "定时任务执行结果"
            }
            manager.createNotificationChannel(channel)
        }

        /**
         * 发一条执行结果通知。
         */
        fun notifyResult(
            context: Context,
            taskName: String,
            summary: String,
            conversationId: String?,
        ) {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (conversationId != null) {
                    putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                }
            }
            val pending = PendingIntent.getActivity(
                context,
                notificationId(conversationId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(taskName)
                .setContentText(summary.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            runCatching { manager.notify(NOTIFICATION_ID_RESULT, notification) }
        }

        private fun notificationId(conversationId: String?): Int =
            (conversationId ?: "task").hashCode()
    }
}

/**
 * 定时任务的执行逻辑。
 *
 * 三种动作：
 * - NOTIFY：发一条通知（支持 {date} {time} 模板变量）
 * - TOOL：调一个工具（参数预填）
 * - AGENT：用指定技能跑一次完整 Agent，结果写进任务的专属会话
 */
class TaskRunner(
    private val container: com.mynok.myagent.di.AppContainer,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一个任务，返回执行摘要。
     */
    suspend fun runTask(taskId: String): String {
        val repo = container.scheduledTaskRepository
        val task = repo.get(taskId) ?: return "任务不存在"
        if (!task.enabled) return "任务已停用"

        return when (task.actionType) {
            ScheduledTaskEntity.ACTION_NOTIFY -> runNotify(task)
            ScheduledTaskEntity.ACTION_TOOL -> runTool(task)
            ScheduledTaskEntity.ACTION_AGENT -> runAgent(task)
            else -> "未知的动作类型：${task.actionType}"
        }.also { summary ->
            val next = container.taskScheduler.schedule(task)
            repo.recordRun(
                id = task.id,
                status = if (summary.startsWith("失败")) "ERROR" else "OK",
                message = summary,
                nextRunAt = next,
            )
        }
    }

    // ------------------------------------------------------------ 通知型

    private fun runNotify(task: ScheduledTaskEntity): String {
        val payload = parsePayload(task.payload)
        val title = payload.str("title").ifBlank { task.name }
        val body = renderTemplate(payload.str("body"))
        val text = if (body.isBlank()) title else "$title：$body"
        TaskRunService.notifyResult(container.app, task.name, text, task.conversationId)
        return "已发送通知：$text"
    }

    // ------------------------------------------------------------ 工具型

    private suspend fun runTool(task: ScheduledTaskEntity): String {
        val payload = parsePayload(task.payload)
        val toolName = payload.str("tool")
        if (toolName.isBlank()) return "失败：没有指定工具"
        val tool = container.toolRegistry.get(toolName)
            ?: return "失败：找不到工具「$toolName」"
        val argsText = payload["args"]?.let { element ->
            if (element is kotlinx.serialization.json.JsonObject) element.toString()
            else runCatching { element.jsonPrimitive.content }.getOrNull()
        }.orEmpty().ifBlank { "{}" }
        val args = runCatching { json.parseToJsonElement(argsText).jsonObject }.getOrNull()
            ?: return "失败：工具参数不是合法 JSON"

        // 定时任务里只允许跑白名单内的工具
        val whitelist = task.allowedTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (whitelist.isNotEmpty() && toolName !in whitelist) {
            return "失败：工具「$toolName」不在该任务的白名单里"
        }
        if (tool.risk == ToolRisk.DESTRUCTIVE) {
            return "失败：定时任务不允许执行破坏性操作"
        }

        val toolContext = AndroidToolContext(
            http = container.http,
            workspace = container.workspace,
            approver = backgroundApprover(whitelist),
            logger = { },
        )
        val result = runCatching { tool.execute(args, toolContext) }.getOrElse {
            return "失败：工具执行异常 ${it.message}"
        }
        val summary = if (result.isError) "失败：${result.content}" else result.content
        TaskRunService.notifyResult(container.app, task.name, summary, task.conversationId)
        return summary
    }

    // ------------------------------------------------------------ Agent 型

    private suspend fun runAgent(task: ScheduledTaskEntity): String {
        val repo = container.repository
        val payload = parsePayload(task.payload)
        val prompt = renderTemplate(payload.str("prompt"))
        if (prompt.isBlank()) return "失败：没有配置要执行的提示词"

        // 找到（或创建）这个任务的专属会话
        val conversationId = task.conversationId ?: run {
            val id = repo.createConversation(
                title = task.name,
                modelProfileId = task.modelProfileId,
            )
            container.scheduledTaskRepository.bindConversation(task.id, id)
            id
        }

        val profileId = task.modelProfileId
            ?: container.repository.firstProfileId()
            ?: return "失败：没有可用的模型档案"
        val profileEntity = repo.getProfile(profileId) ?: return "失败：模型档案不存在"
        val provider = runCatching { repo.providerFor(profileEntity) }.getOrElse {
            return "失败：无法创建模型连接 ${it.message}"
        }
        val profile = repo.toProfile(profileEntity)

        // 组装系统提示：Agent 身份 + 技能正文
        val appSettings = container.settingsStore.settings.first()
        val skill = task.skillId?.let { container.skillRepository.get(it) }
        val system = com.mynok.myagent.core.agent.SystemPrompt.build(
            agentName = appSettings.agentName,
            preferences = appSettings.agentPreferences,
            extraPrompts = listOf(
                "这是一次由定时任务触发的自动执行，请直接给出结果，不要反问。",
                skill?.prompt,
            ),
        )

        // 白名单内的工具视为已授权
        val whitelist = task.allowedTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val toolContext = AndroidToolContext(
            http = container.http,
            workspace = container.workspace,
            approver = backgroundApprover(whitelist),
            logger = { },
        )

        // 写入用户消息
        val history = repo.activePath(conversationId)
        val userMessage = ChatMessage(
            role = Role.USER,
            parts = listOf(ContentPart.Text(prompt)),
        )
        var parent = repo.appendMessage(conversationId, history.lastOrNull()?.id, userMessage)

        val budget = ContextBudget(
            contextWindow = profile.contextWindow,
            reserveForOutput = (profile.maxOutputTokens + 256).coerceAtMost(profile.contextWindow / 4),
        )

        val answer = StringBuilder()
        var failure: String? = null
        container.agentLoop.run(
            provider = provider,
            history = history,
            system = system,
            userMessage = userMessage,
            toolContext = toolContext,
            budget = budget,
            allowedTools = whitelist.toSet().takeIf { it.isNotEmpty() },
        ).collect { event ->
            when (event) {
                is AgentEvent.AssistantDelta -> answer.append(event.text)
                is AgentEvent.MessageProduced -> {
                    parent = repo.appendMessage(conversationId, parent, event.message)
                }
                is AgentEvent.Failed -> failure = event.error.message
                else -> Unit
            }
        }

        failure?.let { return "失败：$it" }
        val summary = answer.toString().trim().ifEmpty { "（没有产生回复）" }
        TaskRunService.notifyResult(container.app, task.name, summary, conversationId)
        return summary
    }

    // ------------------------------------------------------------ 工具方法

    /**
     * 无人值守时的审批策略。
     *
     * 定时任务跑的时候没人能点「同意」，所以：
     * - 白名单里的工具视为已批准（用户建任务时勾选过）
     * - 破坏性操作一律拒绝，再怎么勾选也不放行
     * - 白名单外的工具一律拒绝
     */
    private fun backgroundApprover(allowed: Collection<String>): ToolApprover = { approval ->
        approval.risk != ToolRisk.DESTRUCTIVE && approval.toolName in allowed
    }

    private fun parsePayload(raw: String): JsonObject = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }

    /** 读一个字符串字段；不是字符串就当没有。 */
    private fun JsonObject.str(key: String): String =
        this[key]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.orEmpty()

    /** 替换 {date} {time} {weekday} 这类模板变量。 */
    private fun renderTemplate(text: String): String {
        if (text.isEmpty()) return text
        val now = java.time.ZonedDateTime.now()
        return text
            .replace("{date}", now.toLocalDate().toString())
            .replace("{time}", now.toLocalTime().withSecond(0).withNano(0).toString())
            .replace("{weekday}", "周" + listOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1])
    }
}
