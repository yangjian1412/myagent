package com.mynok.myagent.ui.models

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.model.ModelPresets
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.core.model.RemoteModel
import com.mynok.myagent.data.db.ModelProfileEntity
import kotlinx.coroutines.delay

@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onSave: (
        id: String?,
        name: String,
        providerType: ProviderType,
        baseUrl: String,
        modelId: String,
        apiKey: String?,
        contextWindow: Int,
        maxOutputTokens: Int,
        supportsTools: Boolean,
        supportsVision: Boolean,
        supportsFileInput: Boolean,
        temperature: Double?,
        reasoningLevels: List<ReasoningEffort>,
    ) -> Unit,
    onDelete: (String) -> Unit,
    onProbe: (ModelProfileEntity) -> Unit,
    catalog: CatalogState = CatalogState.Idle,
    onFetchModels: (ProviderType, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onResetCatalog: () -> Unit = {},
    onClearMessage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<ModelProfileEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                state.message?.let { message ->
                    LaunchedEffect(message) {
                        delay(3000)
                        onClearMessage()
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.profiles, key = { it.id }) { profile ->
                ModelCard(
                    profile = profile,
                    probeText = if (state.probeFor == profile.id) {
                        when (val p = state.probe) {
                            is ProbeResult.Success -> "✓ ${p.detail}"
                            is ProbeResult.Failure -> "✗ ${p.detail}"
                            ProbeResult.Probing -> "检测中…"
                            else -> null
                        }
                    } else {
                        null
                    },
                    onEdit = { editing = profile },
                    onDelete = { onDelete(profile.id) },
                    onProbe = { onProbe(profile) },
                )
            }
        }
    }

    if (creating) {
        ModelEditDialog(
            existing = null,
            onDismiss = { creating = false },
            onSave = { args ->
                creating = false
                onSave(
                    args.id, args.name, args.providerType, args.baseUrl, args.modelId,
                    args.apiKey, args.contextWindow, args.maxOutputTokens,
                    args.supportsTools, args.supportsVision, args.supportsFileInput,
                    args.temperature, args.reasoningLevels,
                )
            },
            catalog = catalog,
            onFetchModels = onFetchModels,
            onResetCatalog = onResetCatalog,
        )
    }
    editing?.let { target ->
        ModelEditDialog(
            existing = target,
            onDismiss = { editing = null },
            onSave = { args ->
                editing = null
                onSave(
                    args.id, args.name, args.providerType, args.baseUrl, args.modelId,
                    args.apiKey, args.contextWindow, args.maxOutputTokens,
                    args.supportsTools, args.supportsVision, args.supportsFileInput,
                    args.temperature, args.reasoningLevels,
                )
            },
            catalog = catalog,
            onFetchModels = onFetchModels,
            onResetCatalog = onResetCatalog,
        )
    }
}

@Composable
private fun ModelCard(
    profile: ModelProfileEntity,
    probeText: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onProbe) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = "连接自检")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(
                "${profile.modelId}  ·  ${providerLabel(profile.providerType)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                profile.baseUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (profile.baseUrl.startsWith("http://")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // 四个能力标记在窄屏上放不下，让它能横着推
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                AssistChip(onClick = {}, label = { Text(if (profile.supportsTools) "支持工具调用" else "不支持工具") })
                AssistChip(onClick = {}, label = { Text(if (profile.supportsVision) "支持识图" else "不支持识图") })
                AssistChip(onClick = {}, label = { Text(if (profile.supportsFileInput) "支持文档输入" else "不支持文档") })
                AssistChip(onClick = {}, label = { Text("${profile.contextWindow / 1024}K 上下文") })
            }
            if (probeText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    probeText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

private fun providerLabel(type: String): String = when (type) {
    ProviderType.ANTHROPIC.name -> "Anthropic 原生"
    ProviderType.GEMINI.name -> "Gemini 原生"
    else -> "OpenAI 兼容"
}

data class ModelEditArgs(
    val id: String?,
    val name: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String?,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val supportsFileInput: Boolean,
    val temperature: Double?,
    val reasoningLevels: List<ReasoningEffort> = emptyList(),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelEditDialog(
    existing: ModelProfileEntity?,
    onDismiss: () -> Unit,
    onSave: (ModelEditArgs) -> Unit,
    catalog: CatalogState = CatalogState.Idle,
    onFetchModels: (ProviderType, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onResetCatalog: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onResetCatalog() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // 名称是自动带出来的话，换模型时允许覆盖；用户手改过就不动了
    var nameAuto by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "http://192.168.1.100:8080/v1") }
    var modelId by remember { mutableStateOf(existing?.modelId ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var providerType by remember {
        mutableStateOf(
            runCatching { ProviderType.valueOf(existing?.providerType ?: ProviderType.OPENAI_COMPAT.name) }
                .getOrDefault(ProviderType.OPENAI_COMPAT),
        )
    }
    var supportsTools by remember { mutableStateOf(existing?.supportsTools ?: true) }
    var supportsVision by remember { mutableStateOf(existing?.supportsVision ?: false) }
    var supportsFileInput by remember { mutableStateOf(existing?.supportsFileInput ?: false) }
    var contextWindow by remember { mutableStateOf((existing?.contextWindow ?: 32768).toString()) }
    var maxOutput by remember { mutableStateOf((existing?.maxOutputTokens ?: 4096).toString()) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    // 上限是不是自动填的（提示用）
    var limitsAuto by remember { mutableStateOf(false) }
    // 用户手改过上限就不再自动覆盖
    var limitsEdited by remember { mutableStateOf(false) }
    var reasoningLevels by remember {
        mutableStateOf(ReasoningEffort.parseList(existing?.reasoningLevels).filter { it.isOn })
    }

    /** 手输模型 ID 时，按名字推断能力（用户没手改过上限才覆盖）。 */
    fun applyPresetIfAllowed(id: String) {
        val preset = ModelPresets.lookup(id) ?: return
        if (!limitsEdited) {
            contextWindow = preset.contextWindow.toString()
            maxOutput = preset.maxOutputTokens.toString()
            limitsAuto = true
        }
        preset.supportsVision?.let { supportsVision = it }
        preset.supportsTools?.let { supportsTools = it }
        preset.supportsFileInput?.let { supportsFileInput = it }
        if (preset.reasoningEfforts.isNotEmpty()) {
            reasoningLevels = preset.reasoningEfforts.filter { it.isOn }.sortedBy { it.ordinal }
        }
    }

    /** 从服务端列表里选一个：服务端值优先，缺的用预设补。 */
    fun selectRemoteModel(remote: RemoteModel) {
        modelId = remote.id
        val preset = ModelPresets.lookup(remote.id)
        val ctx = remote.contextWindow ?: preset?.contextWindow
        val out = remote.maxOutputTokens ?: preset?.maxOutputTokens
        if (!limitsEdited) {
            ctx?.let {
                contextWindow = it.toString()
                limitsAuto = true
            }
            out?.let {
                maxOutput = it.toString()
                limitsAuto = true
            }
        }
        (remote.supportsVision ?: preset?.supportsVision)?.let { supportsVision = it }
        (remote.supportsTools ?: preset?.supportsTools)?.let { supportsTools = it }
        preset?.supportsFileInput?.let { supportsFileInput = it }
        val efforts = remote.reasoningEfforts ?: preset?.reasoningEfforts
        if (!efforts.isNullOrEmpty()) {
            reasoningLevels = efforts.filter { it.isOn }.sortedBy { it.ordinal }
        }
        if (name.isBlank() || nameAuto) {
            name = remote.displayName?.takeIf { it.isNotBlank() } ?: remote.id
            nameAuto = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加模型" else "编辑模型") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader("连接")

                ExposedDropdownMenuBox(
                    expanded = providerMenu,
                    onExpandedChange = { providerMenu = !providerMenu },
                ) {
                    OutlinedTextField(
                        value = providerLabel(providerType.name),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false },
                    ) {
                        ProviderType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(providerLabel(type.name)) },
                                onClick = {
                                    providerType = type
                                    providerMenu = false
                                    // 还是默认地址时才跟着换，避免覆盖用户自己填的
                                    if (isDefaultBaseUrl(baseUrl)) {
                                        baseUrl = defaultBaseUrl(type)
                                    }
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(defaultBaseUrl(providerType)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(if (existing?.secretId != null) "API Key（留空则保留原值）" else "API Key")
                    },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { onFetchModels(providerType, baseUrl, apiKey, existing?.secretId) },
                    enabled = baseUrl.isNotBlank() && catalog !is CatalogState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (catalog is CatalogState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在拉取…")
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (catalog is CatalogState.Loaded) "重新拉取模型列表" else "刷新模型列表")
                    }
                }

                when (catalog) {
                    is CatalogState.Loaded -> Text(
                        "✓ 已获取 ${catalog.models.size} 个模型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is CatalogState.Error -> Text(
                        "✗ ${catalog.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        "刷新后可从列表里选模型，也可以直接手填模型 ID。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(2.dp))
                SectionHeader("模型")

                ExposedDropdownMenuBox(
                    expanded = modelMenu,
                    onExpandedChange = {
                        // 没拉到列表时不给展开
                        if (catalog is CatalogState.Loaded) modelMenu = !modelMenu
                    },
                ) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = {
                            modelId = it
                            limitsEdited = false
                            applyPresetIfAllowed(it)
                        },
                        label = { Text("模型 ID") },
                        singleLine = true,
                        trailingIcon = {
                            if (catalog is CatalogState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                    )
                    if (catalog is CatalogState.Loaded) {
                        ExposedDropdownMenu(
                            expanded = modelMenu,
                            onDismissRequest = { modelMenu = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            catalog.models.forEach { remote ->
                                DropdownMenuItem(
                                    text = { RemoteModelItem(remote) },
                                    onClick = {
                                        modelMenu = false
                                        selectRemoteModel(remote)
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameAuto = false
                    },
                    label = { Text("显示名称") },
                    placeholder = { Text("留空则用模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    OutlinedTextField(
                        value = contextWindow,
                        onValueChange = {
                            contextWindow = it.filter(Char::isDigit)
                            limitsEdited = true
                            limitsAuto = false
                        },
                        label = { Text("上下文窗口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = maxOutput,
                        onValueChange = {
                            maxOutput = it.filter(Char::isDigit)
                            limitsEdited = true
                            limitsAuto = false
                        },
                        label = { Text("最大输出") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (limitsAuto) {
                    Text(
                        "上限已按模型自动填写，可手动修改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(2.dp))
                SectionHeader("能力")

                SettingSwitchRow(
                    title = "工具调用",
                    subtitle = "允许该模型调用 function calling 工具",
                    checked = supportsTools,
                    onCheckedChange = { supportsTools = it },
                )
                SettingSwitchRow(
                    title = "识图",
                    subtitle = "支持图片输入（多模态）",
                    checked = supportsVision,
                    onCheckedChange = { supportsVision = it },
                )
                SettingSwitchRow(
                    title = "文档输入",
                    subtitle = "支持把文件（PDF 等）原样发给模型。" +
                        "OpenAI 官方接口、Claude、Gemini 支持；" +
                        "本地推理服务（Ollama、LM Studio）通常不支持。",
                    checked = supportsFileInput,
                    onCheckedChange = { supportsFileInput = it },
                )

                Text("思考强度", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReasoningEffort.entries.filter { it.isOn }.forEach { effort ->
                        val selected = effort in reasoningLevels
                        FilterChip(
                            selected = selected,
                            onClick = {
                                reasoningLevels = if (selected) {
                                    reasoningLevels - effort
                                } else {
                                    reasoningLevels + effort
                                }
                            },
                            label = { Text(effort.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    "不选表示该模型不支持扩展思考。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ModelEditArgs(
                            id = existing?.id,
                            name = name.ifBlank { modelId },
                            providerType = providerType,
                            baseUrl = baseUrl,
                            modelId = modelId,
                            apiKey = apiKey.ifBlank { null },
                            contextWindow = contextWindow.toIntOrNull() ?: 32768,
                            maxOutputTokens = maxOutput.toIntOrNull() ?: 4096,
                            supportsTools = supportsTools,
                            supportsVision = supportsVision,
                            supportsFileInput = supportsFileInput,
                            temperature = null,
                            reasoningLevels = reasoningLevels.sortedBy { it.ordinal },
                        ),
                    )
                },
                enabled = baseUrl.isNotBlank() && modelId.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 分组小标题。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 标题 + 说明 + 右侧开关。 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 下拉里的一个模型：ID + 展示名/上限。 */
@Composable
private fun RemoteModelItem(remote: RemoteModel) {
    Column {
        Text(remote.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(
            remote.displayName?.takeIf { it != remote.id },
            remote.contextWindow?.let { "${it / 1024}K 上下文" },
            remote.maxOutputTokens?.let { "输出 $it" },
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 各协议的默认地址。 */
private fun defaultBaseUrl(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "https://api.anthropic.com"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    ProviderType.OPENAI_COMPAT -> "http://192.168.1.100:8080/v1"
}

/** 判断当前地址是不是某个默认值，只有默认值才跟着协议切换。 */
private fun isDefaultBaseUrl(url: String): Boolean {
    val trimmed = url.trimEnd('/')
    return ProviderType.entries.any { defaultBaseUrl(it).trimEnd('/') == trimmed }
}
