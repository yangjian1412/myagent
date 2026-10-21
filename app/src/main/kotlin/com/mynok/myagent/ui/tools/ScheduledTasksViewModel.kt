package com.mynok.myagent.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.core.schedule.CronExpression
import com.mynok.myagent.core.schedule.TriggerType
import com.mynok.myagent.data.db.ScheduledTaskEntity
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.UUID

/** 定时任务条目（带解析好的描述和下次运行时间）。 */
data class ScheduledTaskEntry(
    val task: ScheduledTaskEntity,
    /** cron 的人类可读描述。 */
    val scheduleText: String,
    /** 下次触发时间（毫秒）。 */
    val nextRunAt: Long?,
    /** cron 表达式是否合法。 */
    val valid: Boolean,
)

data class ScheduledTasksUiState(
    val tasks: List<ScheduledTaskEntry> = emptyList(),
    val models: List<com.mynok.myagent.data.db.ModelProfileEntity> = emptyList(),
    val skills: List<com.mynok.myagent.data.db.SkillEntity> = emptyList(),
    /** 可以放进白名单的工具（只列非破坏性、能在后台跑的）。 */
    val whitelistableTools: List<WhitelistTool> = emptyList(),
    /** 是否有精确闹钟权限。 */
    val canScheduleExact: Boolean = true,
    val message: String? = null,
)

/** 白名单里可选的一个工具。 */
data class WhitelistTool(
    val name: String,
    val description: String,
    val risk: com.mynok.myagent.core.tools.ToolRisk,
)

/**
 * 定时任务页面的状态管理。
 */
class ScheduledTasksViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.scheduledTaskRepository
    private val scheduler = container.taskScheduler

    /** 这些工具要弹界面或需要前台权限，定时任务跑不了。 */
    private val foregroundOnlyTools = setOf(
        "take_photo",
        "record_audio",
        "call",
        "send_intent",
        "open_url",
        "launch_app",
        "list_apps",
    )

    private val _state = MutableStateFlow(ScheduledTasksUiState())
    val state: StateFlow<ScheduledTasksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { tasks -> rebuild(tasks) }
        }
        viewModelScope.launch {
            container.repository.observeProfiles().collect { profiles ->
                _state.update { it.copy(models = profiles) }
            }
        }
        viewModelScope.launch {
            container.skillRepository.observeAll().collect { skills ->
                _state.update { it.copy(skills = skills) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(canScheduleExact = scheduler.canScheduleExact()) }
        }
        viewModelScope.launch {
            val tools = container.toolRegistry.all()
                .filter { !it.spec.name.startsWith("skill_") }
                .filter { it.spec.name !in foregroundOnlyTools }
                .map {
                    WhitelistTool(
                        name = it.spec.name,
                        description = it.spec.description,
                        risk = it.risk,
                    )
                }
                .sortedBy { it.name }
            _state.update { it.copy(whitelistableTools = tools) }
        }
    }

    private fun rebuild(tasks: List<ScheduledTaskEntity>) {
        _state.update { current ->
            current.copy(
                tasks = tasks.map { task ->
                    val cron = CronExpression.parseOrNull(task.cron)
                    ScheduledTaskEntry(
                        task = task,
                        scheduleText = cron?.describe() ?: "表达式无效",
                        nextRunAt = scheduler.nextRunAt(task),
                        valid = cron != null,
                    )
                },
            )
        }
    }

    // ------------------------------------------------------------ 操作

    /**
     * 保存一个任务。
     */
    fun save(
        id: String?,
        name: String,
        triggerType: TriggerType,
        time: LocalTime,
        weekdays: Set<Int>,
        intervalMinutes: Int,
        actionType: String,
        payload: String,
        modelProfileId: String?,
        skillId: String?,
        allowedTools: Set<String>,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val cron = runCatching {
                CronExpression.build(triggerType, time, weekdays, intervalMinutes)
            }.getOrElse {
                _state.update { s -> s.copy(message = "触发规则无效：${it.message}") }
                return@launch
            }
            val taskId = id ?: "task-${UUID.randomUUID()}"
            val existing = repo.get(taskId)
            val task = (existing ?: ScheduledTaskEntity(
                id = taskId,
                name = name,
                cron = cron,
                actionType = actionType,
                createdAt = System.currentTimeMillis(),
            )).copy(
                name = name.ifBlank { "未命名任务" },
                cron = cron,
                actionType = actionType,
                payload = payload,
                enabled = enabled,
                modelProfileId = modelProfileId,
                skillId = skillId,
                allowedTools = allowedTools.joinToString(","),
            )
            repo.upsert(task)
            if (enabled) {
                scheduler.schedule(task)
            } else {
                scheduler.cancel(task.id)
            }
            _state.update { it.copy(message = "已保存任务「${task.name}」") }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setEnabled(id, enabled)
            val task = repo.get(id)
            if (task != null) {
                if (enabled) {
                    scheduler.schedule(task)
                } else {
                    scheduler.cancel(id)
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            scheduler.cancel(id)
            repo.delete(id)
            _state.update { it.copy(message = "已删除任务") }
        }
    }

    /**
     * 立即执行一次（用于测试）。
     */
    fun runNow(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(message = "正在执行任务…") }
            val summary = container.taskRunner.runTask(id)
            _state.update { it.copy(message = "执行结果：${summary.take(120)}") }
        }
    }

    /** 重新登记所有闹钟。 */
    fun rescheduleAll() {
        viewModelScope.launch {
            val tasks = repo.getEnabled()
            scheduler.rescheduleAll(tasks)
            _state.update { it.copy(message = "已重新登记 ${tasks.size} 个任务") }
        }
    }

    /** 请求精确闹钟权限。 */
    fun requestExactAlarm() {
        scheduler.requestExactAlarmPermission()
    }

    fun refreshExactAlarmPermission() {
        viewModelScope.launch {
            _state.update { it.copy(canScheduleExact = scheduler.canScheduleExact()) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ScheduledTasksViewModel(container) as T
        }
    }
}
