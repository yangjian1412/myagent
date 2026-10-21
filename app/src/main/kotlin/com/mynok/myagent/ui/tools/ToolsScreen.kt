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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.ToolSecretKeys
import com.mynok.myagent.data.db.McpServerEntity
import com.mynok.myagent.data.db.SkillEntity

@Composable
fun ToolsScreen(
    state: ToolsUiState,
    onAddMcp: (name: String, url: String, token: String?) -> Unit,
    onRemoveMcp: (String) -> Unit,
    onSyncMcp: () -> Unit,
    onGrantDirectory: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onSetToolEnabled: (String, Boolean) -> Unit,
    onSetToolApproval: (String, Boolean, ToolRisk) -> Unit,
    authorizedRoots: List<String>,
    onSaveSecret: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    var editingSecret by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = onSyncMcp, enabled = !state.busy) {
                Icon(Icons.Filled.Sync, null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.busy) "同步中" else "同步 MCP")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("添加 MCP")
            }
        }

        if (state.message != null) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("集成密钥") }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "第三方服务的 token 会加密后存在本机，工具执行时现取，改完立即生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        ToolSecretKeys.all.forEachIndexed { index, key ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                            SecretRow(
                                name = key,
                                configured = key in state.configuredSecrets,
                                onConfigure = { editingSecret = key },
                            )
                        }
                    }
                }
            }

            item { SectionTitle("文件访问") }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            if (authorizedRoots.isEmpty()) {
                                "还没有授权任何目录，文件类工具（list_files / read_file / write_file / search_files）暂时不可用。"
                            } else {
                                "已授权 ${authorizedRoots.size} 个目录：${authorizedRoots.joinToString()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = onGrantDirectory) {
                            Icon(Icons.Filled.FolderOpen, null)
                            Spacer(Modifier.width(6.dp))
                            Text("授权目录")
                        }
                    }
                }
            }

            item { SectionTitle("系统权限") }
            item { SystemPermissionsCard() }

            item { SectionTitle("内置工具") }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "「screenshot」工具需要先授权屏幕共享，授权后模型才能看到屏幕内容。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = onRequestScreenCapture) {
                            Icon(Icons.Filled.Visibility, null)
                            Spacer(Modifier.width(6.dp))
                            Text("开启屏幕共享")
                        }
                    }
                }
            }
            items(state.builtIn, key = { it.name }) { entry ->
                ToolCard(
                    entry = entry,
                    onEnabledChange = { onSetToolEnabled(entry.name, it) },
                    onApprovalChange = { onSetToolApproval(entry.name, it, entry.risk) },
                )
            }

            item { SectionTitle("MCP 服务") }
            if (state.mcpServers.isEmpty()) {
                item {
                    Text(
                        "还没有配置 MCP 服务。填入 MCP server 的 HTTP 地址即可接入（stdio 类型的服务需要先用 mcp-proxy 之类桥接成 HTTP）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.mcpServers, key = { it.id }) { server ->
                McpServerCard(server = server, onRemove = { onRemoveMcp(server.id) })
            }
        }
    }

    if (adding) {
        AddMcpDialog(
            onDismiss = { adding = false },
            onAdd = { name, url, token ->
                adding = false
                onAddMcp(name, url, token)
            },
        )
    }

    editingSecret?.let { name ->
        SecretDialog(
            name = name,
            configured = name in state.configuredSecrets,
            onDismiss = { editingSecret = null },
            onSave = { value ->
                editingSecret = null
                onSaveSecret(name, value)
            },
        )
    }
}

@Composable
private fun SecretRow(name: String, configured: Boolean, onConfigure: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ToolSecretKeys.label(name), style = MaterialTheme.typography.titleSmall)
            Text(
                ToolSecretKeys.hint(name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        AssistChip(
            onClick = onConfigure,
            label = {
                Text(
                    if (configured) "已配置" else "配置",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (configured) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        )
    }
}

@Composable
private fun SecretDialog(
    name: String,
    configured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 ${ToolSecretKeys.label(name)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    ToolSecretKeys.hint(name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (configured) "新密钥（留空保存则清除）" else "密钥") },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "隐藏" else "显示",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToolCard(
    entry: ToolEntry,
    onEnabledChange: (Boolean) -> Unit,
    onApprovalChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = entry.enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(entry.riskLabel, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = when (entry.risk) {
                            ToolRisk.READ_ONLY -> MaterialTheme.colorScheme.secondaryContainer
                            ToolRisk.WRITE -> MaterialTheme.colorScheme.tertiaryContainer
                            ToolRisk.DESTRUCTIVE -> MaterialTheme.colorScheme.errorContainer
                        },
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (entry.approvalMandatory) "执行前必须确认" else "执行前确认",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (entry.approvalMandatory) {
                    // 破坏性操作不允许关闭确认
                    Switch(checked = true, onCheckedChange = {}, enabled = false)
                } else {
                    Switch(checked = entry.requireApproval, onCheckedChange = onApprovalChange)
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(server: McpServerEntity, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(server.url, style = MaterialTheme.typography.bodySmall)
            if (server.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    server.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, token: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://192.168.1.100:3000/mcp") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL（Streamable HTTP）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer Token（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, token.ifBlank { null }) },
                enabled = url.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
