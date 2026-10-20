package com.mynok.myagent.core.tools.builtin

import java.net.URI

/**
 * 极简 HTML 提取器。
 *
 * 纯正则实现（不引入 jsoup 之类的额外依赖），只做三件事：
 * 1. 剔掉 script/style/nav/footer 这类非正文内容；
 * 2. 抽出标题与正文纯文本（反转义 + 压缩空白）；
 * 3. 抽出链接，并可按 baseUrl 把相对地址解析成绝对地址。
 */
object HtmlExtractor {

    // ---------- 正则 ----------

    private val commentRe = Regex("""(?s)<!--.*?-->""")

    private val scriptRe = Regex("""(?is)<script\b[^>]*>.*?</script\s*>""")

    private val styleRe = Regex("""(?is)<style\b[^>]*>.*?</style\s*>""")

    /** 页面框架类标签：整块丢掉。 */
    private val noiseRe = Regex("""(?is)<(nav|footer|header|aside|form|noscript|svg|iframe|head)\b[^>]*>.*?</\1\s*>""")

    private val titleRe = Regex("""(?is)<title[^>]*>(.*?)</title\s*>""")

    private val h1Re = Regex("""(?is)<h1[^>]*>(.*?)</h1\s*>""")

    private val tagRe = Regex("""(?s)<[^>]*>""")

    private val anchorRe = Regex("""(?is)<a\b([^>]*)>(.*?)</a\s*>""")

    private val hrefRe = Regex("""(?is)\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    private val entityRe = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""")

    private val spaceRunRe = Regex("""[ \t\x0B\f\r]+""")

    private val whitespaceRe = Regex("""[\s ]+""")

    /** 块级标签：换成换行，尽量保留段落结构。 */
    private val blockTags = listOf(
        "address", "article", "aside", "blockquote", "figcaption", "figure", "footer", "header",
        "main", "nav", "section", "summary", "details", "fieldset", "legend", "caption",
        "thead", "tbody", "tfoot", "table", "tr", "td", "th", "li", "ul", "ol", "dl", "dt",
        "dd", "div", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "p", "br", "hr", "form",
    ).sortedByDescending { it.length }

    private val blockTagRe = Regex("""(?i)</?(?:${blockTags.joinToString("|")})(?:\s[^>]*)?/?>""")

    /** 常见命名实体。查找时会统一转小写。 */
    private val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "", "shy" to "",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "minus" to "−",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "sbquo" to "‚", "bdquo" to "„", "laquo" to "«", "raquo" to "»",
        "lsaquo" to "‹", "rsaquo" to "›", "oline" to "‾", "frasl" to "⁄",
        "bull" to "•", "middot" to "·", "dagger" to "†", "permil" to "‰",
        "copy" to "©", "reg" to "®", "trade" to "™", "sect" to "§", "para" to "¶",
        "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup2" to "²", "sup3" to "³",
        "micro" to "µ", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "iexcl" to "¡", "iquest" to "¿", "curren" to "¤", "brvbar" to "¦",
        "not" to "¬", "macr" to "¯", "acute" to "´", "cedil" to "¸", "ordf" to "ª",
        "ordm" to "º", "szlig" to "ß", "agrave" to "à", "aacute" to "á", "auml" to "ä",
        "aring" to "å", "aelig" to "æ", "ccedil" to "ç", "egrave" to "è", "eacute" to "é",
        "euml" to "ë", "igrave" to "ì", "iacute" to "í", "iuml" to "ï", "ntilde" to "ñ",
        "ograve" to "ò", "oacute" to "ó", "ouml" to "ö", "oslash" to "ø", "ugrave" to "ù",
        "uacute" to "ú", "uuml" to "ü", "yacute" to "ý", "yuml" to "ÿ", "alpha" to "α",
        "beta" to "β", "gamma" to "γ", "delta" to "δ", "pi" to "π", "sigma" to "σ",
        "omega" to "ω", "larr" to "←", "uarr" to "↑", "rarr" to "→", "darr" to "↓",
        "harr" to "↔", "crarr" to "↵", "infin" to "∞", "ne" to "≠", "le" to "≤", "ge" to "≥",
    )

    // ---------- 对外 API ----------

    /** 取 <title>；没有则退回第一个 <h1>；都没有返回空串。 */
    fun extractTitle(html: String): String {
        titleRe.find(html)?.let { m ->
            val title = plainText(m.groupValues[1])
            if (title.isNotBlank()) return title
        }
        h1Re.find(html)?.let { m ->
            val title = plainText(m.groupValues[1])
            if (title.isNotBlank()) return title
        }
        return ""
    }

    /** 抽正文纯文本：去噪 → 去标签 → 反转义 → 压缩空白。 */
    fun extractText(html: String): String {
        var s = html
        s = commentRe.replace(s, " ")
        s = scriptRe.replace(s, " ")
        s = styleRe.replace(s, " ")
        s = noiseRe.replace(s, " ")
        s = blockTagRe.replace(s, "\n")
        s = tagRe.replace(s, " ")
        s = unescape(s)
        s = spaceRunRe.replace(s, " ")
        return s.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** 抽链接：返回 (href, 链接文本)；[baseUrl] 非空时把相对地址解析成绝对地址。 */
    fun extractLinks(html: String, baseUrl: String? = null): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        for (m in anchorRe.findAll(html)) {
            val attrs = m.groupValues[1]
            val rawHref = hrefRe.find(attrs)?.let { hm ->
                hm.groupValues[1].ifEmpty { hm.groupValues[2] }.ifEmpty { hm.groupValues[3] }
            }?.trim() ?: continue
            if (rawHref.isEmpty()) continue
            if (rawHref.startsWith("#")) continue
            if (rawHref.startsWith("javascript:", ignoreCase = true)) continue
            if (rawHref.startsWith("data:", ignoreCase = true)) continue
            val text = plainText(m.groupValues[2])
            links += resolve(rawHref, baseUrl) to text
        }
        return links
    }

    /** 去掉标签并反转义，空白压成单个空格。 */
    fun plainText(html: String): String {
        val s = unescape(tagRe.replace(html, " "))
        return whitespaceRe.replace(s, " ").trim()
    }

    /** HTML 实体反转义（只做一遍，不会二次解码）。 */
    fun unescape(text: String): String = entityRe.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) -> {
                val code = body.substring(2).toIntOrNull(16)
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }

            body.startsWith("#") -> {
                val code = body.substring(1).toIntOrNull()
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }

            else -> namedEntities[body.lowercase()] ?: m.value
        }
    }

    // ---------- 内部 ----------

    /** 相对地址 → 绝对地址。解析不了就原样返回。 */
    private fun resolve(href: String, baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return href
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) return href
        if (href.startsWith("//")) return "https:$href"
        return runCatching { URI.create(baseUrl).resolve(href).toString() }.getOrDefault(href)
    }
}
