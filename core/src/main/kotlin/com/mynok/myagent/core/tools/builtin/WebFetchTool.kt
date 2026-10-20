package com.mynok.myagent.core.tools.builtin

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 抓取网页并转成纯文本。
 *
 * 只走网络，不需要任何 Android 权限。HTML 会用 [HtmlExtractor] 抽正文，
 * 非 HTML 直接返回原始内容。
 */
class WebFetchTool(
    private val http: Http? = null,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "web_fetch",
        description = "抓取一个网页并返回可读正文（HTML 会自动去标签、反转义、压缩空白）。" +
            "参数：url（必填，http/https 链接）、maxChars（可选，默认 8000，最大字符数）。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "要抓取的网页地址，必须是 http/https 链接")
                })
                put("maxChars", buildJsonObject {
                    put("type", "integer")
                    put("description", "正文最大字符数，默认 8000")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("url")) })
        },
    )

    override val risk: ToolRisk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.stringArg("url", "link", "page")?.trim()
            ?: return ToolResult.error("缺少参数 url（要抓取的网页地址）")
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ToolResult.error("仅支持 http/https 协议，收到：$url")
        }

        val maxChars = (args.intArg("maxChars", "max_chars", "limit") ?: DEFAULT_MAX_CHARS)
            .coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

        val client = http ?: ctx.http
        ctx.log("web_fetch $url")

        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    ),
                    timeoutSeconds = 45,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error("抓取失败：${describeNetworkError(e)}")
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "抓取失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml")

        val title: String
        val body: String
        if (isHtml) {
            title = HtmlExtractor.extractTitle(response.body)
            body = HtmlExtractor.extractText(response.body)
        } else {
            title = ""
            body = response.body
        }

        if (body.isBlank()) {
            return ToolResult.ok(
                buildString {
                    if (title.isNotBlank()) appendLine("标题: $title")
                    append("（页面没有可提取的正文内容）")
                }.trim(),
            )
        }

        val clipped = if (body.length > maxChars) {
            body.take(maxChars) + "\n…（内容过长，已截断至 $maxChars 字符）"
        } else {
            body
        }

        return ToolResult.ok(
            if (title.isNotBlank()) "标题: $title\n\n$clipped" else clipped,
        )
    }

    companion object {
        private const val DEFAULT_MAX_CHARS = 8_000
        private const val MIN_MAX_CHARS = 200
        private const val MAX_MAX_CHARS = 100_000

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
