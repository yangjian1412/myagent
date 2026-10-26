package com.mynok.myagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
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

    // 只有用户本来就贴着底部时才跟着滚。
    // 否则模型流式输出会把人硬拽回底部：想翻历史看不了，
    // 系统的长截图也没法往上滚着截。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) {
                // 还没排版，先当作贴着底部，免得第一条消息不自动上屏
                true
            } else {
                val last = items.last()
                last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 1
            }
        }
    }

    LaunchedEffect(total, streamingText.length) {
        // atBottom 在协程里读，不会成为 effect 的 key（否则会自激）
        if (total > 0 && atBottom) {
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
            SelectableBody {
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
                message.files.forEach { file ->
                    Spacer(Modifier.height(6.dp))
                    FilePlaceholder(file)
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
            SelectableBody {
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

@Composable
private fun FilePlaceholder(file: ContentPart.File) {
    // 文件以 Base64 存库，这里只显示文件名与类型
    val sizeKb = file.base64.length * 3 / 4 / 1024
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Description, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "${file.name}（${file.mimeType}，约 ${sizeKb}KB）",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 可选中的正文。
 *
 * 长按能选中文字、局部复制，也能「全选」整条。
 *
 * 用的是 foundation 的 [SelectionContainer]：它弹出来的是真正的
 * `android.view.ActionMode`，浮动窗口由系统绘制（剪切 / 复制 / 粘贴 / 全选 / 分享）。
 *
 * 想要 Android 13+ 那种带「智能选词 / 翻译 / 搜索」的系统菜单，得走
 * `CommonContextMenuArea` + 平台的 `TextClassificationHelper`——但那两个在
 * Compose 1.12 里都是 internal，应用模块用不了，所以暂时只能到经典这一档。
 */
@Composable
private fun SelectableBody(content: @Composable () -> Unit) {
    val state = rememberSelectionState()
    SelectionContainer(state) {
        content()
    }
}

/**
 * 轻量 Markdown 渲染：只处理代码块、行内换行与可点链接。
 *
 * 链接用 [LinkAnnotation.Url] 标出来，点一下交给系统处理（浏览器、地图、拨号盘都算）。
 * 整段包在 [SelectableBody] 里：长按能选中文字、局部复制，也能「全选」整条。
 */
@Composable
fun MarkdownishText(text: String, color: Color) {
    val segments = androidx.compose.runtime.remember(text) { splitCodeBlocks(text) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // 模型写出来的网址不一定合法，交给系统时抛异常不能把 App 带崩
    val openUri: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, "没有应用能打开这个链接", Toast.LENGTH_SHORT).show()
        }
    }
    SelectableBody {
        Column {
            segments.forEach { seg ->
                if (seg.isCode) {
                    CodeBlock(seg.text, seg.lang)
                } else {
                    Text(
                        text = linkify(seg.text, openUri),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 代码块。
 *
 * 右上角一个复制按钮。正文用软换行而不是横向滚动：
 * 横向可滚的子视图容易被系统长截图的滚动识别和拼接搞乱，
 * 软换行顺带让长行不再被截断。
 */
@Composable
private fun CodeBlock(code: String, lang: String? = null) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang ?: "代码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                // 复制按钮自己关掉选中：不然长按它会变成选文本
                DisableSelection {
                    IconButton(
                        onClick = { copyToClipboard(context, code, "代码") },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制代码",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }
}

/** 复制到剪贴板，并给用户一个提示。 */
private fun copyToClipboard(context: Context, text: String, label: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null) {
        Toast.makeText(context, "复制失败：拿不到剪贴板服务", Toast.LENGTH_SHORT).show()
        return
    }
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
}

/**
 * 把文本里的网址变成可点的链接。
 *
 * 结尾的标点要摘出来：中文句子里「看这里 https://a.com/x，然后」的逗号不算网址的一部分，
 * 括号同理，`https://a.com/x_(y)` 的右括号也不该跟着进去。
 */
private val URL_PATTERN = Regex("""https?://[^\s<>"'】]+""")

/** 网址结尾一般不带这些字符。 */
private const val TRAILING_PUNCTUATION = ".,;:!?，。、；：！？)）】]》」』\"'"

private fun linkify(text: String, onUri: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var index = 0
    URL_PATTERN.findAll(text).forEach { match ->
        if (match.range.first > index) {
            append(text.substring(index, match.range.first))
        }
        val matched = match.value
        val trailing = matched.takeLastWhile { it in TRAILING_PUNCTUATION }
        val url = matched.removeSuffix(trailing)
        val start = length
        append(url)
        addLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = Color(0xFF1A73E8),
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { onUri(url) },
            ),
            start,
            length,
        )
        // 摘掉的标点原样还回去，摆在链接外面
        append(trailing)
        index = match.range.last + 1
    }
    if (index < text.length) {
        append(text.substring(index))
    }
}

private data class Segment(val text: String, val isCode: Boolean, val lang: String? = null)

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
            val inner = text.substring(i + 3, end)
            // ```kotlin 这种语言标记写在开栏同一行，要摘出来当标签，
            // 混进代码里会让第一行变成 "kotlin"
            var lang: String? = null
            var body = inner.trim('\n')
            val newlineAt = inner.indexOf('\n')
            if (newlineAt > 0) {
                val head = inner.substring(0, newlineAt).trim()
                if (head.isNotEmpty() && head.length <= 24 && head.none { it.isWhitespace() }) {
                    lang = head
                    body = inner.substring(newlineAt + 1).trim('\n')
                }
            }
            out += Segment(body, true, lang)
            i = end + 3
        } else {
            buffer.append(text[i])
            i++
        }
    }
    if (buffer.isNotEmpty()) out += Segment(buffer.toString(), false)
    return out
}
