package com.mynok.myagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    isStreaming: Boolean,
    traces: List<ToolTrace>,
    onRegenerate: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    streamingReasoning: String = "",
    showReasoning: Boolean = true,
    showToolCalls: Boolean = true,
) {
    val listState = rememberLazyListState()
    // 关闭「显示工具调用」时把工具消息从列表里真正移除，
    // 但保留原始下标，重新生成 / 编辑仍然对应到正确的节点。
    val visible = remember(messages, showReasoning, showToolCalls) {
        messages.withIndex().filter { (_, message) ->
            isMessageVisible(message, showReasoning, showToolCalls)
        }
    }
    val total = visible.size + if (isStreaming) 1 else 0
    LaunchedEffect(total, streamingText.length) {
        if (total > 0) {
            runCatching { listState.animateScrollToItem((total - 1).coerceAtLeast(0)) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = visible, key = { it.index }) { (index, msg) ->
            MessageBubble(
                message = msg,
                showReasoning = showReasoning,
                showToolCalls = showToolCalls,
                onRegenerate = { onRegenerate(index) },
                onEdit = { onEdit(index) },
            )
        }
        if (isStreaming) {
            item(key = "__streaming__") {
                StreamingBubble(
                    text = streamingText,
                    reasoning = streamingReasoning,
                    showReasoning = showReasoning,
                )
            }
        }
        if (showToolCalls && traces.isNotEmpty()) {
            item(key = "__traces__") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    traces.forEach { ToolTraceRow(it) }
                }
            }
        }
    }
}

/** 关闭工具调用展示时，隐藏工具结果和「只有调用、没有可见内容」的助手消息。 */
private fun isMessageVisible(
    message: ChatMessage,
    showReasoning: Boolean,
    showToolCalls: Boolean,
): Boolean {
    if (showToolCalls) return true
    if (message.role == Role.TOOL) return false
    val hasBody = message.text.isNotBlank() || message.images.isNotEmpty()
    val hasReasoning = showReasoning && message.reasoning.isNotBlank()
    return hasBody || hasReasoning
}

/** 可折叠的思考过程。 */
@Composable
private fun ReasoningBlock(text: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onRegenerate: (String) -> Unit,
    onEdit: (String) -> Unit,
    showReasoning: Boolean = true,
    showToolCalls: Boolean = true,
) {
    val isUser = message.role == Role.USER
    val isTool = message.role == Role.TOOL

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isTool) {
            ToolResultBubble(message)
            return@Column
        }

        if (showReasoning && message.reasoning.isNotBlank()) {
            Box(modifier = Modifier.widthIn(max = 560.dp)) {
                ReasoningBlock(message.reasoning, live = false)
            }
            Spacer(Modifier.height(6.dp))
        }

        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                MarkdownishText(
                    text = message.text,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                message.images.forEach { img ->
                    Spacer(Modifier.height(6.dp))
                    ImagePlaceholder(img)
                }
                if (showToolCalls && message.hasToolCalls) {
                    Spacer(Modifier.height(6.dp))
                    message.toolCalls.forEach { call ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    "调用 ${call.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        )
                    }
                }
            }
        }

        if (!isUser && message.toolCalls.isEmpty() && message.text.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onRegenerate(message.text) }) { Text("重新生成") }
            }
        }
        if (isUser) {
            Row {
                TextButton(onClick = { onEdit(message.text) }) { Text("编辑重发") }
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    reasoning: String = "",
    showReasoning: Boolean = true,
) {
    Column(modifier = Modifier.widthIn(max = 560.dp)) {
        if (showReasoning && reasoning.isNotBlank()) {
            ReasoningBlock(reasoning, live = true)
            Spacer(Modifier.height(6.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("思考中…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    MarkdownishText(text, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToolResultBubble(message: ChatMessage) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "工具结果 · ${message.name.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                message.text.take(600),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolTraceRow(trace: ToolTrace) {
    val (icon, tint) = when (trace.status) {
        ToolTrace.Status.RUNNING -> Icons.Filled.AutoAwesome to MaterialTheme.colorScheme.primary
        ToolTrace.Status.DONE -> Icons.Filled.Check to MaterialTheme.colorScheme.tertiary
        ToolTrace.Status.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        ToolTrace.Status.DENIED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(6.dp))
                Text(trace.toolName, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                if (trace.durationMs > 0) {
                    Text(
                        "${trace.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trace.arguments.isNotBlank() && trace.arguments != "{}") {
                Spacer(Modifier.height(4.dp))
                Text(
                    trace.arguments.take(300),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trace.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    trace.error.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(image: ContentPart.Image) {
    // 图片以 Base64 存库，这里只显示占位与文件名，避免为预览引入额外图片库
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.BugReport, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                image.displayName ?: "图片（${image.mimeType}，${image.base64.length / 1024}KB Base64）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 轻量 Markdown 渲染：只处理代码块与行内换行，避免引入完整 Markdown 依赖。
 */
@Composable
fun MarkdownishText(text: String, color: Color) {
    val segments = androidx.compose.runtime.remember(text) { splitCodeBlocks(text) }
    Column {
        segments.forEach { seg ->
            if (seg.isCode) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        seg.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            } else {
                Text(
                    seg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private data class Segment(val text: String, val isCode: Boolean)

private fun splitCodeBlocks(text: String): List<Segment> {    val out = mutableListOf<Segment>()
    var i = 0
    val buffer = StringBuilder()
    while (i < text.length) {
        if (text.startsWith("```", i)) {
            if (buffer.isNotEmpty()) {
                out += Segment(buffer.toString(), false)
                buffer.setLength(0)
            }
            val end = text.indexOf("```", i + 3)
            if (end < 0) {
                out += Segment(text.substring(i), true)
                break
            }
            out += Segment(text.substring(i + 3, end).trim('\n'), true)
            i = end + 3
        } else {
            buffer.append(text[i])
            i++
        }
    }
    if (buffer.isNotEmpty()) out += Segment(buffer.toString(), false)
    return out
}
