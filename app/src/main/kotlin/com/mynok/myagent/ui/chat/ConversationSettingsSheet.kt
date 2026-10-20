package com.mynok.myagent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.data.db.ModelProfileEntity

/**
 * 对话设置面板。
 *
 * 换模型、换思考强度、开关思考过程与工具调用展示——都在这里，不离开对话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsSheet(
    models: List<ModelProfileEntity>,
    currentModelId: String?,
    supportedEfforts: List<ReasoningEffort>,
    reasoningEffort: ReasoningEffort,
    showReasoning: Boolean,
    showToolCalls: Boolean,
    onSelectModel: (String) -> Unit,
    onReasoningEffort: (ReasoningEffort) -> Unit,
    onShowReasoning: (Boolean) -> Unit,
    onShowToolCalls: (Boolean) -> Unit,
    onShowContext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "对话设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(16.dp))
            Label("模型")
            Spacer(Modifier.height(6.dp))
            if (models.isEmpty()) {
                Text(
                    "还没有配置模型，先到「模型」页添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            models.forEach { model ->
                val selected = model.id == currentModelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectModel(model.id) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            model.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${model.modelId} · ${model.providerType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Label("思考强度")
            Spacer(Modifier.height(8.dp))
            if (supportedEfforts.isEmpty()) {
                Text(
                    "当前模型没有声明支持扩展思考，可在「模型」页编辑该模型并勾选支持的强度等级。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val options = listOf(ReasoningEffort.OFF) + supportedEfforts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { effort ->
                        FilterChip(
                            selected = reasoningEffort == effort,
                            onClick = { onReasoningEffort(effort) },
                            label = { Text(effort.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "等级来自当前模型档案，不同模型可选的档位可能不同。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示思考过程", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "在回复上方折叠展示模型的思考内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showReasoning, onCheckedChange = onShowReasoning)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示工具调用", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "展示调用记录、参数与返回结果；关闭后只保留最终回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showToolCalls, onCheckedChange = onShowToolCalls)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            FilledTonalButton(
                onClick = onShowContext,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Insights, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("查看上下文检查器")
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
