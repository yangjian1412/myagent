package com.mynok.myagent.core.tools.builtin

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.ToolSecretKeys
import com.mynok.myagent.core.tools.ToolSecrets
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网页搜索工具。
 *
 * 支持三个提供方：
 * - duckduckgo：免 key，走 html.duckduckgo.com，用 [HtmlExtractor] 解析；
 * - tavily：需要 API key；
 * - brave：需要 API key。
 *
 * key 由构造参数传入（不放进 [ToolContext]，避免跟着每次调用到处传）。
 */
class WebSearchTool(
    private val http: Http? = null,
    private val tavilyApiKey: String? = null,
    private val braveApiKey: String? = null,
    private val secrets: ToolSecrets? = null,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        description = "搜索网页并返回标题/链接/摘要。" +
            "参数：query（必填，搜索词）、count（可选，默认 5，最大 10）、" +
            "provider（可选，bing 默认 / duckduckgo / tavily / brave，后两个需要配置 API Key）。" +
            "注意：duckduckgo 在国内通常无法访问，优先用默认的 bing。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "搜索关键词")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "返回结果条数，默认 5，最大 10")
                })
                put("provider", buildJsonObject {
                    put("type", "string")
                    put("description", "搜索提供方：bing（默认，免 key，国内可访问）/ duckduckgo / tavily / brave")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("bing"))
                        add(JsonPrimitive("duckduckgo"))
                        add(JsonPrimitive("tavily"))
                        add(JsonPrimitive("brave"))
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        },
    )

    override val risk: ToolRisk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.stringArg("query", "q", "keyword", "search")?.trim()
            ?: return ToolResult.error("缺少参数 query（搜索关键词）")
        if (query.isEmpty()) return ToolResult.error("参数 query 不能为空")

        val count = (args.intArg("count", "num", "limit") ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
        val provider = (args.stringArg("provider", "engine") ?: DEFAULT_PROVIDER).trim().lowercase()

        val client = http ?: ctx.http
        ctx.log("web_search provider=$provider count=$count")

        return try {
            when (provider) {
                "bing", "cn_bing", "cn" -> searchBing(client, ctx, query, count)
                "duckduckgo", "ddg", "ddg_html" -> searchDuckDuckGo(client, ctx, query, count)
                "tavily" -> searchTavily(client, ctx, query, count)
                "brave" -> searchBrave(client, ctx, query, count)
                else -> ToolResult.error(
                    "未知的搜索提供方：$provider（可选 bing / duckduckgo / tavily / brave）",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("搜索失败：${describeNetworkError(e)}")
        }
    }

    // ---------- Bing ----------

    private suspend fun searchBing(
        client: Http,
        ctx: ToolContext,
        query: String,
        count: Int,
    ): ToolResult {
        val url = "$BING_ENDPOINT?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-Hans"
        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    ),
                    timeoutSeconds = 30,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error("Bing 搜索失败：${describeNetworkError(e)}")
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "Bing 搜索失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val hits = parseBing(response.body, count)
        if (hits.isEmpty()) {
            return ToolResult.ok("没有找到与「$query」相关的结果。")
        }
        return ToolResult.ok(formatHits(hits))
    }

    /**
     * 解析 Bing 结果页。
     *
     * 结构：`<li class="b_algo">` → `<h2><a href="...">标题</a></h2>` +
     * `<div class="b_caption"><p>摘要</p></div>`。Bing 偶尔把摘要换成别的容器，
     * 摘要缺失不影响标题和链接返回。
     */
    private fun parseBing(html: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val blocks = html.split("<li class=\"b_algo\"")
        for (block in blocks.drop(1)) {
            if (hits.size >= limit) break

            // 标题链接一定在 <h2> 里，避免把前面的站点图标链接当成结果
            val head = block.substring(block.indexOf("<h2").takeIf { it >= 0 } ?: 0)
            val anchor = ANCHOR_RE.find(head) ?: continue
            val href = hrefOf(anchor.groupValues[1]) ?: continue
            if (!href.startsWith("http")) continue
            if (href.contains("bing.com/") && !href.contains("bing.com/search")) continue

            val title = HtmlExtractor.plainText(anchor.groupValues[2])
            val snippet = SNIPPET_RE.find(block)?.let { HtmlExtractor.plainText(it.groupValues[1]) }.orEmpty()

            hits += SearchHit(title = title, url = href, snippet = snippet)
        }
        return hits
    }


    // ---------- DuckDuckGo ----------

    private suspend fun searchDuckDuckGo(
        client: Http,
        ctx: ToolContext,
        query: String,
        count: Int,
    ): ToolResult {
        val url = "$DUCKDUCKGO_ENDPOINT?q=" + URLEncoder.encode(query, "UTF-8")
        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    ),
                    timeoutSeconds = 30,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error(
                "DuckDuckGo 搜索失败：${describeNetworkError(e)}\n" +
                    "（DuckDuckGo 在中国大陆通常无法直连，改用 provider=bing 试试）",
            )
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "DuckDuckGo 搜索失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val hits = parseDuckDuckGo(response.body, count)
        if (hits.isEmpty()) {
            return ToolResult.ok("没有找到与「$query」相关的结果。")
        }
        return ToolResult.ok(formatHits(hits))
    }

    /** 解析 html.duckduckgo.com 的结果页：结果标题是 a.result__a，摘要是 a.result__snippet。 */
    private fun parseDuckDuckGo(html: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        var current: SearchHit? = null

        for (m in ANCHOR_RE.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val classes = classesOf(attrs)
            when {
                "result__a" in classes -> {
                    val href = hrefOf(attrs) ?: continue
                    val link = normalizeDuckDuckGoUrl(href)
                    if (link.isEmpty()) continue
                    current = SearchHit(
                        title = HtmlExtractor.plainText(inner),
                        url = link,
                    )
                    hits += current
                    if (hits.size >= limit) break
                }

                "result__snippet" in classes -> {
                    val snippet = HtmlExtractor.plainText(inner)
                    if (snippet.isNotBlank()) current?.snippet = snippet
                }
            }
        }
        return hits.take(limit)
    }

    /** DuckDuckGo 的结果链接是跳转链接，需要还原成真实地址。 */
    private fun normalizeDuckDuckGoUrl(href: String): String {
        val raw = href.trim()
        val marker = "uddg="
        if (raw.contains("/l/") && raw.contains(marker)) {
            val encoded = raw.substring(raw.indexOf(marker) + marker.length).substringBefore('&')
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
            if (decoded.startsWith("http")) return decoded
        }
        if (raw.startsWith("//")) return "https:$raw"
        return raw
    }

    // ---------- Tavily ----------

    private suspend fun searchTavily(
        client: Http,
        ctx: ToolContext,
        query: String,
        count: Int,
    ): ToolResult {
        val key = tavilyKey()
            ?: return ToolResult.error("未配置 Tavily API Key，无法使用 tavily 搜索。请在「工具 → 集成密钥」里填写。")

        val body = buildJsonObject {
            put("api_key", JsonPrimitive(key))
            put("query", JsonPrimitive(query))
            put("max_results", JsonPrimitive(count))
        }.toString()

        val response = try {
            client.execute(
                HttpRequest(
                    url = TAVILY_ENDPOINT,
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                    body = body,
                    timeoutSeconds = 45,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error("Tavily 搜索失败：${e.message ?: e::class.simpleName}")
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "Tavily 搜索失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val json = parseJsonObject(response.body)
            ?: return ToolResult.error("Tavily 返回了无法解析的 JSON：${HttpUtils.summarize(response.body)}")

        val results = json["results"] as? JsonArray
            ?: return ToolResult.ok("没有找到与「$query」相关的结果。")

        val hits = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.textField("url") ?: return@mapNotNull null
            SearchHit(
                title = obj.textField("title").orEmpty(),
                url = url,
                snippet = obj.textField("content").orEmpty(),
            )
        }.take(count)

        if (hits.isEmpty()) return ToolResult.ok("没有找到与「$query」相关的结果。")
        return ToolResult.ok(formatHits(hits))
    }

    // ---------- Brave ----------

    private suspend fun searchBrave(
        client: Http,
        ctx: ToolContext,
        query: String,
        count: Int,
    ): ToolResult {
        val key = braveKey()
            ?: return ToolResult.error("未配置 Brave API Key，无法使用 brave 搜索。请在「工具 → 集成密钥」里填写。")

        val url = "$BRAVE_ENDPOINT?q=" + URLEncoder.encode(query, "UTF-8") + "&count=$count"

        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Subscription-Token" to key,
                    ),
                    timeoutSeconds = 45,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error("Brave 搜索失败：${e.message ?: e::class.simpleName}")
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "Brave 搜索失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val json = parseJsonObject(response.body)
            ?: return ToolResult.error("Brave 返回了无法解析的 JSON：${HttpUtils.summarize(response.body)}")

        val results = (json["web"] as? JsonObject)?.get("results") as? JsonArray
            ?: return ToolResult.ok("没有找到与「$query」相关的结果。")

        val hits = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val link = obj.textField("url") ?: return@mapNotNull null
            SearchHit(
                title = obj.textField("title").orEmpty(),
                url = link,
                snippet = obj.textField("description").orEmpty(),
            )
        }.take(count)

        if (hits.isEmpty()) return ToolResult.ok("没有找到与「$query」相关的结果。")
        return ToolResult.ok(formatHits(hits))
    }

    // ---------- 输出 ----------

    private suspend fun tavilyKey(): String? =
        secrets?.get(ToolSecretKeys.TAVILY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: tavilyApiKey?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun braveKey(): String? =
        secrets?.get(ToolSecretKeys.BRAVE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: braveApiKey?.trim()?.takeIf { it.isNotEmpty() }

    private fun formatHits(hits: List<SearchHit>): String = buildString {
        hits.forEachIndexed { index, hit ->
            if (index > 0) appendLine()
            appendLine("${index + 1}. ${hit.title.ifBlank { "(无标题)" }}")
            appendLine("   ${hit.url}")
            if (hit.snippet.isNotBlank()) appendLine("   ${hit.snippet}")
        }
    }.trimEnd()

    private fun parseJsonObject(body: String): JsonObject? =
        runCatching { MyAgentJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun classesOf(attrs: String): Set<String> {
        val match = CLASS_RE.find(attrs) ?: return emptySet()
        val raw = match.groupValues[1]
            .ifEmpty { match.groupValues[2] }
            .ifEmpty { match.groupValues[3] }
        return raw.split(' ', '\t', '\n').filter { it.isNotBlank() }.toSet()
    }

    private fun hrefOf(attrs: String): String? {
        val match = HREF_RE.find(attrs) ?: return null
        val value = match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }
        return value.trim().takeIf { it.isNotEmpty() }
    }

    /** 一条搜索结果。 */
    private class SearchHit(
        val title: String,
        val url: String,
        var snippet: String = "",
    )

    companion object {
        private const val DEFAULT_COUNT = 5
        private const val MAX_COUNT = 10

        /** 默认用 Bing：国内可直连，DuckDuckGo 经常被墙。 */
        private const val DEFAULT_PROVIDER = "bing"

        private const val BING_ENDPOINT = "https://cn.bing.com/search"
        private const val DUCKDUCKGO_ENDPOINT = "https://html.duckduckgo.com/html/"
        private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
        private const val BRAVE_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private val ANCHOR_RE = Regex("""(?is)<a\b([^>]*)>(.*?)</a\s*>""")
        private val CLASS_RE = Regex("""(?is)\bclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        private val HREF_RE = Regex("""(?is)\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

        /** Bing 摘要：优先 b_caption 里的段落。 */
        private val SNIPPET_RE = Regex(
            """(?is)<div[^>]*class="[^"]*b_caption[^"]*"[^>]*>.*?<p[^>]*>(.*?)</p\s*>""",
        )
    }
}

/** 把网络异常翻译成人话，方便模型（和用户）判断是墙、超时还是证书问题。 */
internal fun describeNetworkError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "域名解析失败（${e.message}），可能被墙或 DNS 异常"
    is java.net.SocketTimeoutException -> "连接超时，站点不可达或响应太慢"
    is java.net.ConnectException -> "连接被拒绝/不可达（${e.message}）"
    is javax.net.ssl.SSLException -> "TLS 握手失败（${e.message}），可能是证书问题"
    else -> e.message ?: e::class.simpleName ?: "未知错误"
}

/** 读一个字符串字段；不是字符串就返回 null。 */
private fun JsonObject.textField(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}
