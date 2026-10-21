package com.mynok.myagent.core.model

import java.util.Base64

/**
 * 附件内容转文本。
 *
 * 文本类文件（代码、配置、Markdown…）没必要当二进制发：
 * 模型读文本更准，也更省 token。所以协议适配器在发请求前，
 * 会先试 [asTextContent]——能转成文本就转，
 * 转不了（PDF、图片等）才走各家的文档/文件格式。
 *
 * 判断只看 MIME 与扩展名，最后再由 [asTextContent] 按实际字节兜底：
 * 标签骗人的时候（比如 `.json` 里装了二进制）会退回文档格式。
 */
object ContentParts {

    /** 文本附件发给模型的最大字符数。再多会把上下文窗口挤掉，不如截断。 */
    const val MAX_TEXT_CHARS = 120_000

    /** 明明是二进制 MIME、但按扩展名也当文本读的（代码、配置）。 */
    private val TEXT_EXTENSIONS = setOf(
        "md", "markdown", "txt", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "csv", "tsv", "xml", "html", "htm", "css", "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "py", "rb", "go", "rs", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "swift",
        "php", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql", "r", "lua", "dart", "scala",
        "gradle", "properties", "env", "gitignore", "dockerfile", "makefile", "cmake",
        "vue", "svelte", "graphql", "proto", "tex", "bib", "diff", "patch",
    )

    /** MIME 标成 application 系列、但内容其实是文本的少数几个。 */
    private val TEXTUAL_APPLICATION_MIME = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/x-sh",
        "application/sql",
        "application/yaml",
        "application/x-yaml",
    )

    /** 根本没有扩展名、内容却是文本的文件名。 */
    private val TEXT_FILENAMES = setOf(
        "makefile", "dockerfile", "jenkinsfile", "gemfile", "rakefile", "procfile",
        "cmakelists", "readme", "license", "copying", "notice", "authors",
        "changelog", "contributors", "todo", "changes",
    )

    /**
     * 这个附件是不是文本类的。
     *
     * 系统给的类型经常是 `application/octet-stream`（等于没说），
     * 所以文件名与扩展名的优先级更高，MIME 只用来兜它们对不上的情况。
     * 判错了也不要紧：[asTextContent] 还会按实际字节再确认一次。
     */
    fun isTextual(mimeType: String, name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (fileName in TEXT_FILENAMES) return true
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in TEXT_EXTENSIONS) return true
        val mime = mimeType.lowercase().substringBefore(';').trim()
        return mime.startsWith("text/") || mime in TEXTUAL_APPLICATION_MIME
    }

    /**
     * 把文本类附件转成可以直接当文本发的内容。
     *
     * 太长会截断并注明，免得一个日志文件把上下文挤满。
     *
     * @return null 表示这个附件不是文本类（或者字节解不出 UTF-8），
     *   调用方应该改走文档格式
     */
    fun ContentPart.File.asTextContent(): String? {
        if (!isTextual(mimeType, name)) return null
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        // 解出来一堆替换字符说明根本不是 UTF-8，当二进制处理
        if (text.count { it == '\uFFFD' } > text.length / 100 + 4) return null
        val truncated = text.length > MAX_TEXT_CHARS
        val body = if (truncated) text.take(MAX_TEXT_CHARS) else text
        return buildString {
            append("以下是用户发送的文件「").append(name).append("」的内容：")
            append('\n').append('\n')
            append(body)
            if (truncated) {
                append("\n\n（文件太长，到这里截断了，一共还有 ")
                append((text.length - MAX_TEXT_CHARS) / 1000)
                append("k 个字符没发过来）")
            }
        }
    }
}
