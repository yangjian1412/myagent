package com.mynok.myagent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * 模型输出里的 Markdown 解析。
 *
 * 只认模型真会写出来的那些语法：标题、列表、引用、表格、分隔线、围栏代码块、段落。
 * 列表里的多行续行会并到同一个列表项里。粗体 / 斜体 / 行内代码 / 链接不在这里处理，
 * 它们在 [parseInline] 里。
 */
internal sealed interface MdBlock {
    /** 普通段落，内部换行保留，空行分段。 */
    data class Paragraph(val text: String) : MdBlock

    /** `#` 到 `######` 的标题，level 是井号数量。 */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** 引用块，`>` 前缀已经剥掉。 */
    data class Quote(val text: String) : MdBlock

    /**
     * 列表项。marker 只在有序列表里有（就是源文里写的那个 "3."），无序传 null。
     * level 是缩进层级，两个空格算一级。
     */
    data class Bullet(val level: Int, val marker: String?, val text: String) : MdBlock

    /** 围栏代码块。 */
    data class Code(val code: String, val lang: String?) : MdBlock

    /** `---` 这类分隔线。 */
    object Rule : MdBlock

    /** 表格，第一行是表头，分隔行（`|---|`）丢掉。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*\S)\s*$""")
private val RULE = Regex("""^\s*(?:[-*_]\s*){3,}$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val BULLET = Regex("""^(\s*)([-*+]|\d{1,9}[.)])[ \t]+(.*\S)\s*$""")
private val TABLE_DELIM = Regex("""^\s*\|?[\s:|-]*-{1,}[\s:|-]*\|?\s*$""")

internal fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = ArrayList<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }

        // 围栏代码块：开栏同一行的单词是语言标记，混进代码会让第一行变成 "kotlin"
        if (line.trimStart().startsWith("```")) {
            val head = line.trim().substring(3).trim()
            val lang = if (head.isNotEmpty() && head.length <= 24 && head.none { it.isWhitespace() }) {
                head
            } else {
                null
            }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // 吃掉闭栏；没闭栏就当内容到结尾
            out.add(MdBlock.Code(body.toString(), lang))
            continue
        }

        if (RULE.matches(line)) {
            out.add(MdBlock.Rule)
            i++
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }

        if (QUOTE.matchEntire(line) != null) {
            val sb = StringBuilder()
            while (i < lines.size) {
                val quote = QUOTE.matchEntire(lines[i])
                if (quote != null) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(quote.groupValues[1])
                    i++
                    continue
                }
                // 懒延续：引用后边紧跟的不带 > 的行也归引用，空行或下一块开头才停
                if (lines[i].isBlank() || isBlockStart(lines, i)) break
                sb.append('\n').append(lines[i].trim())
                i++
            }
            out.add(MdBlock.Quote(sb.toString()))
            continue
        }

        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            val indent = bullet.groupValues[1].replace('\t', ' ')
            val level = indent.length / 2
            val marker = bullet.groupValues[2]
            val ordered = marker.first().isDigit()
            val text = StringBuilder(bullet.groupValues[3])
            i++
            while (i < lines.size) {
                val cont = lines[i]
                if (cont.isBlank() || BULLET.matchEntire(cont) != null || isBlockStart(lines, i)) {
                    break
                }
                text.append('\n').append(cont.trim())
                i++
            }
            out.add(MdBlock.Bullet(level, if (ordered) marker else null, text.toString()))
            continue
        }

        if (isTableStart(lines, i)) {
            val header = splitRow(lines[i])
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                rows.add(splitRow(lines[i]))
                i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }

        // 普通段落：读到空行或下一块的开头为止
        val sb = StringBuilder()
        while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines, i)) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lines[i].trim())
            i++
        }
        out.add(MdBlock.Paragraph(sb.toString()))
    }
    return out
}

private fun isBlockStart(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    return line.trimStart().startsWith("```") ||
        HEADING.matchEntire(line) != null ||
        RULE.matches(line) ||
        QUOTE.matchEntire(line) != null ||
        BULLET.matchEntire(line) != null ||
        isTableStart(lines, i)
}

/** 表头行 + 下面紧跟分隔行（`|---|`）才算表格。 */
private fun isTableStart(lines: List<String>, i: Int): Boolean {
    if (!lines[i].contains('|')) return false
    val delim = lines.getOrNull(i + 1) ?: return false
    return delim.contains('|') && delim.contains('-') && TABLE_DELIM.matchEntire(delim) != null
}

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.removePrefix("|")
    if (s.endsWith("|") && s.isNotEmpty()) s = s.removeSuffix("|")
    return s.split('|').map { it.trim() }
}

/**
 * 行内解析：粗体（`**`/`__`）、斜体（`*`/`_`）、删除线、行内代码、
 * `[文字](地址)` 链接和裸网址。嵌套的（粗体里再套斜体）不做，够用。
 */
private val INLINE_TOKEN = Regex(
    """\*\*(.+?)\*\*""" +
        """|__([^_\n]+?)__""" +
        """|`([^`\n]+)`""" +
        """|~~(.+?)~~""" +
        """|\[([^\]]*)\]\(([^\s)]+)\)""" +
        // 星号斜体：贴着 `*` 的不算（`**` 走粗体那条），首尾贴空白的不算（"2 * 3 * 4"）
        """|(?<!\*)\*(?!\s)([^*\n]+?)(?<!\s)\*(?!\*)""" +
        // 下划线斜体：拦住 snake_case 这种词内下划线；汉字旁边的照旧允许
        """|(?<![A-Za-z0-9_])_(?!_)(?!\s)([^_\n]+?)(?<!\s)_(?![A-Za-z0-9_])""" +
        // 裸网址：常见 ASCII 与中文标点不往网址里吃（中文句子里的逗号、括号本来就是标点）
        """|https?://[^\s<>"'】，。、；：！？）》」』]+""",
)

private val BOLD_SPAN = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC_SPAN = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE_SPAN = SpanStyle(textDecoration = TextDecoration.LineThrough)

/** 行内代码：等宽 + 一层淡淡底色，深浅色主题下都不刺眼。 */
private val CODE_SPAN = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = Color(0x22808080L),
)

private val LINK_STYLES = TextLinkStyles(
    style = SpanStyle(
        color = Color(0xFF1A73E8),
        textDecoration = TextDecoration.Underline,
    ),
)

/** 网址结尾一般不带这些字符。 */
private const val TRAILING_PUNCTUATION = ".,;:!?，。、；：！？)）】]》」』\"'"

internal fun parseInline(text: String, onUri: (String) -> Unit) = buildAnnotatedString {
    var index = 0
    INLINE_TOKEN.findAll(text).forEach { match ->
        if (match.range.first > index) {
            append(text.substring(index, match.range.first))
        }
        val group = match.groupValues
        fun styled(span: SpanStyle, inner: String) {
            addStyle(span, length, length + inner.length)
            append(inner)
        }
        fun link(label: String, url: String) {
            addLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = LINK_STYLES,
                    linkInteractionListener = { onUri(url) },
                ),
                length,
                length + label.length,
            )
            append(label)
        }
        when {
            group[1] != "" -> styled(BOLD_SPAN, group[1])
            group[2] != "" -> styled(BOLD_SPAN, group[2])
            group[3] != "" -> styled(CODE_SPAN, group[3])
            group[4] != "" -> styled(STRIKE_SPAN, group[4])
            match.groups[5] != null -> {
                // `[文字](地址)`；文字省略了就干脆把地址当显示文字（`[](url)`）
                link(group[5].ifEmpty { group[6] }, group[6])
            }
            group[7] != "" -> styled(ITALIC_SPAN, group[7])
            group[8] != "" -> styled(ITALIC_SPAN, group[8])
            else -> {
                // 裸网址：中文句子里结尾的逗号、括号不算网址的一部分
                val matched = match.value
                val trailing = matched.takeLastWhile { it in TRAILING_PUNCTUATION }
                val url = matched.removeSuffix(trailing)
                link(url, url)
                append(trailing)
            }
        }
        index = match.range.last + 1
    }
    if (index < text.length) {
        append(text.substring(index))
    }
}
