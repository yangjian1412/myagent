package com.mynok.myagent.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.schedule.CronExpression
import com.mynok.myagent.core.schedule.TriggerType
import com.mynok.myagent.data.db.ScheduledTaskEntity
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 定时任务页。
 *
 * 一个任务 = 触发规则 + 动作。
 * 动作有三种：发通知、调一个工具、跑一次 Agent（用指定技能）。
 */
@Composable
fun ScheduledTasksScreen(
    state: ScheduledTasksUiState,
    onSave: (
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
    ) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRunNow: (String) -> Unit,
    onRequestExactAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("新建任务")
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.canScheduleExact) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "没有精确闹钟权限，任务可能会晚几分钟触发。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestExactAlarm) { Text("去开启") }
                        }
                    }
                }
            }

            if (state.tasks.isEmpty()) {
                item {
                    Text(
                        "还没有定时任务。可以让 App 在固定时间发通知、查日程，" +
                            "或者用某个技能跑一次完整的对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.tasks, key = { it.task.id }) { entry ->
                ScheduledTaskCard(
                    entry = entry,
                    onToggle = { enabled -> onSetEnabled(entry.task.id, enabled) },
                    onEdit = { editing = entry.task },
                    onRunNow = { onRunNow(entry.task.id) },
                    onDelete = { pendingDelete = entry.task },
                )
            }
        }
    }

    if (creating || editing != null) {
        TaskEditDialog(
            existing = editing,
            state = state,
            onDismiss = { creating = false; editing = null },
            onSave = { id, name, type, time, weekdays, interval, actionType, payload, modelId, skillId, tools, enabled ->
                creating = false
                editing = null
                onSave(id, name, type, time, weekdays, interval, actionType, payload, modelId, skillId, tools, enabled)
            },
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务？") },
            text = { Text("「${task.name}」的闹钟会被取消，已产生的会话会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(task.id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    entry: ScheduledTaskEntry,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = entry.task
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = task.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onRunNow) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "立即执行")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(
                "${actionLabel(task.actionType)}　${entry.scheduleText}",
                style = MaterialTheme.typography.bodySmall,
            )
            val details = buildList {
                entry.nextRunAt?.let {
                    add("下次：${formatTime(it)}")
                }
                if (task.lastStatus.isNotBlank()) {
                    val mark = if (task.lastStatus == "OK") "✓" else "✗"
                    add("上次：$mark ${task.lastMessage.take(40)}")
                }
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    details.joinToString("　"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun actionLabel(actionType: String): String = when (actionType) {
    ScheduledTaskEntity.ACTION_NOTIFY -> "发通知"
    ScheduledTaskEntity.ACTION_TOOL -> "调用工具"
    ScheduledTaskEntity.ACTION_AGENT -> "运行技能"
    else -> "未知"
}

private fun formatTime(millis: Long): String =
    ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable
private fun TaskEditDialog(
    existing: ScheduledTaskEntity?,
    state: ScheduledTasksUiState,
    onDismiss: () -> Unit,
    onSave: (
        String?,
        String,
        TriggerType,
        LocalTime,
        Set<Int>,
        Int,
        String,
        String,
        String?,
        String?,
        Set<String>,
        Boolean,
    ) -> Unit,
) {
    // 打开既有任务时，把 cron 和 payload 还原成表单能表达的样子
    val initialPreset = remember(existing?.id) {
        existing?.cron?.let { CronExpression.parseOrNull(it)?.toPreset() }
    }
    val initialPayload = remember(existing?.id) {
        existing?.payload?.let { parseTaskPayload(it) } ?: TaskPayload()
    }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var triggerType by remember {
        mutableStateOf(initialPreset?.type ?: TriggerType.DAILY)
    }
    var hour by remember { mutableStateOf(initialPreset?.hour ?: 8) }
    var minute by remember { mutableStateOf(initialPreset?.minute ?: 0) }
    var weekdays by remember { mutableStateOf(initialPreset?.weekdays ?: setOf(1)) }
    var intervalMinutes by remember { mutableStateOf(initialPreset?.intervalMinutes ?: 60) }
    var actionType by remember {
        mutableStateOf(existing?.actionType ?: ScheduledTaskEntity.ACTION_NOTIFY)
    }
    var notifyTitle by remember { mutableStateOf(initialPayload.title) }
    var notifyBody by remember { mutableStateOf(initialPayload.body) }
    var toolName by remember { mutableStateOf(initialPayload.tool) }
    var toolArgs by remember { mutableStateOf(initialPayload.args) }
    var prompt by remember { mutableStateOf(initialPayload.prompt) }
    var modelId by remember { mutableStateOf(existing?.modelProfileId) }
    var skillId by remember { mutableStateOf(existing?.skillId) }
    var whitelist by remember {
        mutableStateOf(
            existing?.allowedTools.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
        )
    }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("触发规则", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TriggerType.entries.forEach { type ->
                        FilterChip(
                            selected = triggerType == type,
                            onClick = { triggerType = type },
                            label = { Text(type.label) },
                        )
                    }
                }

                when (triggerType) {
                    TriggerType.INTERVAL -> {
                        Text("每隔多少分钟", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            CronExpression.minuteIntervalOptions.forEach { minutes ->
                                FilterChip(
                                    selected = intervalMinutes == minutes,
                                    onClick = { intervalMinutes = minutes },
                                    label = { Text("$minutes 分钟") },
                                )
                            }
                        }
                        Text("或者每隔几小时", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            CronExpression.hourIntervalOptions.forEach { hours ->
                                FilterChip(
                                    selected = intervalMinutes == hours * 60,
                                    onClick = { intervalMinutes = hours * 60 },
                                    label = { Text("$hours 小时") },
                                )
                            }
                        }
                    }

                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = hour.toString().padStart(2, '0'),
                                onValueChange = { hour = it.toIntOrNull()?.coerceIn(0, 23) ?: 8 },
                                label = { Text("时") },
                                singleLine = true,
                                modifier = Modifier.width(90.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = minute.toString().padStart(2, '0'),
                                onValueChange = { minute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                                label = { Text("分") },
                                singleLine = true,
                                modifier = Modifier.width(90.dp),
                            )
                        }
                        if (triggerType == TriggerType.WEEKLY) {
                            Text("星期", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..7).forEach { day ->
                                    FilterChip(
                                        selected = day in weekdays,
                                        onClick = {
                                            weekdays = if (day in weekdays) {
                                                weekdays - day
                                            } else {
                                                weekdays + day
                                            }
                                        },
                                        label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]) },
                                    )
                                }
                            }
                        }
                    }
                }

                Text("执行内容", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ScheduledTaskEntity.ACTION_NOTIFY to "发通知",
                        ScheduledTaskEntity.ACTION_TOOL to "调工具",
                        ScheduledTaskEntity.ACTION_AGENT to "跑技能",
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = actionType == type,
                            onClick = { actionType = type },
                            label = { Text(label) },
                        )
                    }
                }

                when (actionType) {
                    ScheduledTaskEntity.ACTION_NOTIFY -> {
                        OutlinedTextField(
                            value = notifyTitle,
                            onValueChange = { notifyTitle = it },
                            label = { Text("通知标题") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = notifyBody,
                            onValueChange = { notifyBody = it },
                            label = { Text("通知内容（可用 {date} {time}）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ScheduledTaskEntity.ACTION_TOOL -> {
                        OutlinedTextField(
                            value = toolName,
                            onValueChange = { toolName = it },
                            label = { Text("工具名，如 calendar") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = toolArgs,
                            onValueChange = { toolArgs = it },
                            label = { Text("参数（JSON）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ScheduledTaskEntity.ACTION_AGENT -> {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("要执行的提示词") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("使用的技能", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = { skillId = null },
                                label = { Text("不指定") },
                            )
                            state.skills.take(6).forEach { skill ->
                                FilterChip(
                                    selected = skillId == skill.id,
                                    onClick = { skillId = skill.id },
                                    label = { Text(skill.name, maxLines = 1) },
                                )
                            }
                        }
                        Text("使用的模型", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.models.forEach { model ->
                                FilterChip(
                                    selected = modelId == model.id,
                                    onClick = { modelId = model.id },
                                    label = { Text(model.name, maxLines = 1) },
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用这个任务", style = MaterialTheme.typography.bodyMedium)
                }

                if (actionType != ScheduledTaskEntity.ACTION_NOTIFY) {
                    Text("允许使用的工具", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "定时任务运行时没人能点确认。勾选的工具视为已批准；" +
                            "破坏性操作无论如何都不放行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.whitelistableTools.isEmpty()) {
                        Text(
                            "没有可用的工具",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.whitelistableTools.forEach { tool ->
                                FilterChip(
                                    selected = tool.name in whitelist,
                                    onClick = {
                                        whitelist = if (tool.name in whitelist) {
                                            whitelist - tool.name
                                        } else {
                                            whitelist + tool.name
                                        }
                                    },
                                    label = { Text(tool.name, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val payload = when (actionType) {
                    ScheduledTaskEntity.ACTION_NOTIFY ->
                        """{"title":"${notifyTitle.escapeJson()}","body":"${notifyBody.escapeJson()}"}"""

                    ScheduledTaskEntity.ACTION_TOOL ->
                        """{"tool":"${toolName.escapeJson()}","args":${toolArgs.ifBlank { "{}" }}}"""

                    else -> """{"prompt":"${prompt.escapeJson()}"}"""
                }
                onSave(
                    existing?.id,
                    name,
                    triggerType,
                    LocalTime.of(hour, minute),
                    weekdays,
                    intervalMinutes,
                    actionType,
                    payload,
                    modelId,
                    skillId,
                    whitelist,
                    enabled,
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

/** 任务 payload 拆开后的样子。 */
private data class TaskPayload(
    val title: String = "",
    val body: String = "",
    val tool: String = "",
    val args: String = "{}",
    val prompt: String = "",
)

/**
 * 解析任务 payload，用于编辑时回填表单。
 *
 * 解析不了就返回空对象——宁可让用户重填，也不要把整条任务搞坏。
 */
private fun parseTaskPayload(raw: String): TaskPayload {
    val element = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
    }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return TaskPayload()
    fun str(key: String): String =
        element[key]?.let {
            runCatching { (it as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull()
        }.orEmpty()
    val args = element["args"]?.let {
        if (it is kotlinx.serialization.json.JsonObject) it.toString() else str("args")
    }.orEmpty().ifBlank { "{}" }
    return TaskPayload(
        title = str("title"),
        body = str("body"),
        tool = str("tool"),
        args = args,
        prompt = str("prompt"),
    )
}
