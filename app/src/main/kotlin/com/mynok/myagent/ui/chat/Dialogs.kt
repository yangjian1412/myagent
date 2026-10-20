package com.mynok.myagent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.context.AssembledContext
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.di.PendingApproval

/**
 * 上下文检查器：展示「最终到底发了什么给模型」。
 */
@Composable
fun ContextInspectorDialog(
    context: AssembledContext?,
    onDismiss: () -> Unit,
) {
    if (context == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文检查器") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "预估 ${context.estimatedTokens} / ${context.budget} tokens" +
                        if (context.compacted) "（已发生压缩或裁剪）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (context.droppedNodeIds.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "被裁剪/压缩：${context.droppedNodeIds.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                context.messages.forEachIndexed { index, msg ->
                    val roleLabel = when (msg.role) {
                        Role.SYSTEM -> "系统"
                        Role.USER -> "用户"
                        Role.ASSISTANT -> "助手"
                        Role.TOOL -> "工具"
                    }
                    Text(
                        "#${index + 1} $roleLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        msg.text.take(1200).ifBlank { "(无文本内容)" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                context.system?.let {
                    Text("系统提示", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        it.take(1000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 危险工具的二次确认。 */
@Composable
fun ApprovalDialog(
    pending: PendingApproval?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("需要确认：${pending.approval.toolName}") },
        text = {
            Column {
                Text(pending.approval.reason)
                if (pending.approval.detail.isNotBlank() && pending.approval.detail != "{}") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pending.approval.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onApprove) { Text("允许") } },
        dismissButton = { OutlinedButton(onClick = onDeny) { Text("拒绝") } },
    )
}
