package com.mynok.myagent.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.data.db.SkillEntity
import com.mynok.myagent.data.skills.SkillTemplates

/**
 * 技能页。
 *
 * 技能是**文件**：一个目录一个技能，里面放 SKILL.md。
 * 用户在「选择技能目录」里授权一个目录后，就能用手机文件管理器直接增删改，
 * 也能让模型用 skill_write 工具写新技能。
 */
@Composable
fun SkillsScreen(
    skills: List<SkillEntity>,
    directoryBound: Boolean,
    directoryName: String,
    onBindDirectory: () -> Unit,
    onRescan: () -> Unit,
    onCreateFromTemplate: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<SkillEntity?>(null) }
    var viewing by remember { mutableStateOf<SkillEntity?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            if (directoryBound) {
                FilledTonalButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "技能以文件形式存放：一个技能一个目录，里面必须有 SKILL.md，" +
                                "格式兼容 Agent Skills 与 ClawHub。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (directoryBound) {
                            Text(
                                "当前目录：$directoryName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row {
                                OutlinedButton(onClick = onBindDirectory) {
                                    Icon(Icons.Filled.FolderOpen, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("更换目录")
                                }
                            }
                        } else {
                            Text(
                                "还没有选择技能目录。选一个手机上的文件夹——「下载」或「文档」里的子文件夹最稳妥，" +
                                    "根目录常常因系统隐私保护不让授权。" +
                                    "App 会在它下面建一个 skills 子目录，" +
                                    "之后就能直接用文件管理器放技能文件。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onBindDirectory) {
                                Icon(Icons.Filled.FolderOpen, null)
                                Spacer(Modifier.width(6.dp))
                                Text("选择技能目录")
                            }
                        }
                    }
                }
            }

            if (skills.isEmpty()) {
                item {
                    Text(
                        if (directoryBound) {
                            "还没有技能。在技能目录里放一个 SKILL.md，或者让模型帮你写一个。"
                        } else {
                            "选择技能目录后就能添加技能了。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (directoryBound) {
                item {
                    Text(
                        "从模板创建",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkillTemplates.all.forEach { template ->
                            AssistChip(
                                onClick = { onCreateFromTemplate(template.slug) },
                                label = { Text(template.name, maxLines = 1) },
                            )
                        }
                    }
                }
            }

            items(skills, key = { it.name }) { skill ->
                SkillCard(
                    skill = skill,
                    onView = { viewing = skill },
                    onToggle = { enabled -> onSetEnabled(skill.id, enabled) },
                    onDelete = { pendingDelete = skill },
                )
            }
        }
    }

    viewing?.let { skill ->
        SkillDetailDialog(skill = skill, onDismiss = { viewing = null })
    }

    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除技能？") },
            text = {
                Text(
                    if (skill.source == SkillEntity.SOURCE_FILE) {
                        "「${skill.name}」的技能文件会一起删除，这个操作不可撤销。"
                    } else {
                        "「${skill.name}」将被移除，已发送的消息不受影响。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(skill.id)
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
private fun SkillCard(
    skill: SkillEntity,
    onView: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val emoji = skill.emoji?.let { "$it " }.orEmpty()
                Text(
                    "$emoji${skill.name}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onView) {
                    Icon(Icons.Filled.Visibility, contentDescription = "查看")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val notes = buildList {
                if (skill.source == SkillEntity.SOURCE_FILE) add("来自文件")
                if (skill.version.isNotBlank()) add("v${skill.version}")
                if (skill.license.isNotBlank()) add(skill.license)
                if (skill.requiredEnv.isNotBlank()) add("需要密钥：${skill.requiredEnv}")
                if (skill.requiredBins.isNotBlank()) add("需要命令行：${skill.requiredBins}")
                if (skill.requiredOs.isNotBlank()) add("仅限：${skill.requiredOs}")
                if (skill.forceTools.isNotBlank()) add("限定工具：${skill.forceTools}")
            }
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    notes.joinToString("　"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SkillDetailDialog(skill: SkillEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (skill.description.isNotBlank()) {
                    Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (skill.prompt.isNotBlank()) {
                    Text(
                        skill.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("（没有正文）", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
