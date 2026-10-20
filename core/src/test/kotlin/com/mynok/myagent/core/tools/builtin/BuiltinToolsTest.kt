package com.mynok.myagent.core.tools.builtin

import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.testing.FakeHttp
import com.mynok.myagent.core.testing.FakeToolContext
import com.mynok.myagent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置工具测试：HTML 抽取、网页搜索、网页抓取、Notion 未配置 token 的处理。
 */
class BuiltinToolsTest {

    // ------------------------------------------------------------------ HtmlExtractor

    @Test
    fun `extractTitle 取出 title 文本`() {
        val html = "<html><head><title>示例页面</title></head><body><p>正文</p></body></html>"
        assertEquals("示例页面", HtmlExtractor.extractTitle(html))
    }

    @Test
    fun `extractTitle 没有 title 时退回第一个 h1`() {
        val html = "<html><body><h1>大标题</h1><h2>小标题</h2></body></html>"
        assertEquals("大标题", HtmlExtractor.extractTitle(html))
    }

    @Test
    fun `extractTitle 既没有 title 也没有 h1 时返回空串`() {
        assertEquals("", HtmlExtractor.extractTitle("<html><body><p>正文</p></body></html>"))
    }

    @Test
    fun `extractText 去掉 script 与 style 只保留正文`() {
        val html = """
            <html>
            <head><title>T</title><style>body{color:red}</style></head>
            <body>
            <script>var x = 1;</script>
            <nav>导航栏</nav>
            <h1>标题</h1>
            <p>第一段正文</p>
            <p>第二段正文</p>
            </body>
            </html>
        """.trimIndent()

        val text = HtmlExtractor.extractText(html)

        assertTrue("应保留第一段正文，实际：$text", text.contains("第一段正文"))
        assertTrue("应保留第二段正文，实际：$text", text.contains("第二段正文"))
        assertTrue("应保留标题，实际：$text", text.contains("标题"))
        assertFalse("不应包含脚本内容，实际：$text", text.contains("var x"))
        assertFalse("不应包含样式内容，实际：$text", text.contains("color:red"))
        assertFalse("不应包含导航栏，实际：$text", text.contains("导航栏"))
    }

    @Test
    fun `extractText 会反转义 HTML 实体`() {
        val html = "<p>Tom &amp; Jerry &lt;tag&gt; &quot;引号&quot; &#65; &#x42; &nbsp;空格</p>"

        val text = HtmlExtractor.extractText(html)

        assertTrue("应反转义 &amp;，实际：$text", text.contains("Tom & Jerry"))
        assertTrue("应反转义 &lt;/&gt;，实际：$text", text.contains("<tag>"))
        assertTrue("应反转义 &quot;，实际：$text", text.contains("\"引号\""))
        assertTrue("应还原数字实体，实际：$text", text.contains("A"))
        assertTrue("应还原十六进制实体，实际：$text", text.contains("B"))
        assertFalse("不应残留实体，实际：$text", text.contains("&amp;"))
    }

    @Test
    fun `extractLinks 抽出链接并把相对地址转成绝对地址`() {
        val html = """
            <a href="/docs">文档</a>
            <a href="https://example.com/x">外链</a>
            <a href="#anchor">锚点</a>
            <a href="javascript:void(0)">脚本链接</a>
            <a href="mailto:a@b.com">邮件</a>
        """.trimIndent()

        val links = HtmlExtractor.extractLinks(html, "https://example.com/base/")

        assertEquals(3, links.size)
        assertEquals("https://example.com/docs", links[0].first)
        assertEquals("文档", links[0].second)
        assertEquals("https://example.com/x", links[1].first)
        assertEquals("外链", links[1].second)
        assertEquals("mailto:a@b.com", links[2].first)
    }

    @Test
    fun `extractLinks 没有 baseUrl 时保留原始地址`() {
        val links = HtmlExtractor.extractLinks("<a href=\"/docs\">文档</a>")
        assertEquals(1, links.size)
        assertEquals("/docs", links[0].first)
    }

    // ------------------------------------------------------------------ WebSearchTool

    private val ddgHtml = """
        <div class="result results_links web-result">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=abc">示例标题</a></h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage">这是搜索结果的摘要</a>
        </div>
    """.trimIndent()

    @Test
    fun `WebSearchTool 解析 DuckDuckGo 结果返回标题与链接`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = ddgHtml))
        val tool = WebSearchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(
            buildJsonObject {
                put("query", "Kotlin 教程")
                put("provider", "duckduckgo")
            },
            ctx,
        )

        assertFalse("搜索不应失败：${result.content}", result.isError)
        assertTrue("结果里应包含标题，实际：${result.content}", result.content.contains("示例标题"))
        assertTrue(
            "结果里应包含还原后的真实链接，实际：${result.content}",
            result.content.contains("https://example.com/page"),
        )
        assertTrue("结果里应包含摘要，实际：${result.content}", result.content.contains("这是搜索结果的摘要"))
        assertTrue("请求应发往 DuckDuckGo", http.requests.single().url.contains("duckduckgo.com"))
    }

    private val bingHtml = """
        <ol id="b_results">
          <li class="b_algo"><h2><a href="https://example.com/b1">必应结果一</a></h2>
            <div class="b_caption"><p class="b_lineclamp2">摘要一</p></div></li>
          <li class="b_algo"><h2><a href="https://example.com/b2">必应结果二</a></h2>
            <div class="b_caption"><p>摘要二</p></div></li>
        </ol>
    """.trimIndent()

    @Test
    fun `WebSearchTool 默认用 Bing 并解析标题链接摘要`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = bingHtml))
        val tool = WebSearchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("query", "天气") }, ctx)

        assertFalse("搜索不应失败：${result.content}", result.isError)
        assertTrue("应包含第一条标题，实际：${result.content}", result.content.contains("必应结果一"))
        assertTrue("应包含第二条链接，实际：${result.content}", result.content.contains("https://example.com/b2"))
        assertTrue("应包含摘要，实际：${result.content}", result.content.contains("摘要二"))
        assertTrue("默认应请求 Bing", http.requests.single().url.contains("bing.com"))
    }

    @Test
    fun `WebSearchTool 缺少 query 参数时返回错误结果而不抛异常`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = ddgHtml))
        val tool = WebSearchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(JsonObject(emptyMap()), ctx)

        assertTrue("缺参数时应返回错误结果", result.isError)
        assertTrue("错误信息应说明缺少 query，实际：${result.content}", result.content.contains("缺少参数 query"))
    }

    @Test
    fun `WebSearchTool 未知 provider 时返回错误结果`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = ddgHtml))
        val tool = WebSearchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(
            buildJsonObject {
                put("query", "x")
                put("provider", "google")
            },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("未知的搜索提供方"))
    }

    @Test
    fun `WebSearchTool 缺少 Tavily Key 时返回错误结果`() = runTest {
        val tool = WebSearchTool(FakeHttp())
        val ctx = FakeToolContext()

        val result = tool.execute(
            buildJsonObject {
                put("query", "x")
                put("provider", "tavily")
            },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("未配置 Tavily API Key"))
    }

    // ------------------------------------------------------------------ WebFetchTool

    @Test
    fun `WebFetchTool 抓取 HTML 后抽取正文`() = runTest {
        val html = """
            <html><head><title>抓取页面</title></head>
            <body><h1>大标题</h1><p>正文内容</p><script>bad()</script></body></html>
        """.trimIndent()
        val http = FakeHttp(
            HttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                body = html,
            ),
        )
        val tool = WebFetchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("url", "https://example.com/page") }, ctx)

        assertFalse("抓取不应失败：${result.content}", result.isError)
        assertTrue("应包含标题，实际：${result.content}", result.content.contains("抓取页面"))
        assertTrue("应包含正文，实际：${result.content}", result.content.contains("正文内容"))
        assertFalse("不应包含脚本，实际：${result.content}", result.content.contains("bad()"))
    }

    @Test
    fun `WebFetchTool 非 http 协议返回错误结果`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = "x"))
        val tool = WebFetchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("url", "ftp://example.com/a.txt") }, ctx)

        assertTrue(result.isError)
        assertTrue(
            "错误信息应说明仅支持 http/https，实际：${result.content}",
            result.content.contains("仅支持 http/https"),
        )
    }

    @Test
    fun `WebFetchTool 缺少 url 参数返回错误结果`() = runTest {
        val tool = WebFetchTool(FakeHttp())
        val ctx = FakeToolContext()

        val result = tool.execute(JsonObject(emptyMap()), ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("缺少参数 url"))
    }

    @Test
    fun `WebFetchTool HTTP 失败返回错误结果`() = runTest {
        val http = FakeHttp(HttpResponse(404, body = "not found"))
        val tool = WebFetchTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("url", "https://example.com/missing") }, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("404"))
    }

    // ------------------------------------------------------------------ Notion 工具

    @Test
    fun `NotionSearchTool 没有 token 时返回未配置的错误结果`() = runTest {
        val tool = NotionSearchTool(http = FakeHttp(), token = null)
        val ctx = FakeToolContext()

        val result = tool.execute(buildJsonObject { put("query", "项目") }, ctx)

        assertTrue(result.isError)
        assertTrue(
            "错误信息应提示未配置 Notion token，实际：${result.content}",
            result.content.contains("未配置 Notion token"),
        )
    }

    @Test
    fun `NotionCreatePageTool 没有 token 时返回未配置的错误结果`() = runTest {
        val tool = NotionCreatePageTool(http = FakeHttp(), token = null)
        val ctx = FakeToolContext()

        val result = tool.execute(buildJsonObject { put("title", "新页面") }, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("未配置 Notion token"))
    }

    @Test
    fun `NotionFetchPageTool 没有 token 时返回未配置的错误结果`() = runTest {
        // TODO 被测代码已知问题：NotionFetchPageTool 把 NotionApiResult.Err 吞掉了，
        // token 未配置时最终返回的是「页面没有内容块」的正常结果（isError=false），
        // 模型会误以为页面真的是空的。这里按正确行为断言，用例当前会失败。
        val tool = NotionFetchPageTool(http = FakeHttp(), token = null)
        val ctx = FakeToolContext()

        val result = tool.execute(
            buildJsonObject { put("page_id", "1234567890abcdef1234567890abcdef") },
            ctx,
        )

        assertTrue("未配置 token 时应返回错误结果", result.isError)
        assertTrue(
            "错误信息应提示未配置 Notion token，实际：${result.content}",
            result.content.contains("未配置 Notion token"),
        )
    }

    @Test
    fun `NotionSearchTool 配置了 token 时正常请求并格式化结果`() = runTest {
        val http = FakeHttp(
            HttpResponse(
                code = 200,
                body = """
                    {"results":[
                      {"object":"page","id":"11111111111111111111111111111111",
                       "url":"https://notion.so/page","title":[{"plain_text":"设计文档"}]},
                      {"object":"database","id":"22222222222222222222222222222222",
                       "title":[{"plain_text":"任务库"}]}
                    ]}
                """.trimIndent(),
            ),
        )
        val tool = NotionSearchTool(http = http, token = "secret-token")
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("query", "设计") }, ctx)

        assertFalse("搜索不应失败：${result.content}", result.isError)
        assertTrue(result.content.contains("设计文档"))
        assertTrue(result.content.contains("任务库"))
        assertTrue(result.content.contains("数据库"))
        assertEquals("Bearer secret-token", http.requests.single().headers["Authorization"])
    }

    // ------------------------------------------------------------ 合并后的 notion 工具

    @Test
    fun `notion 工具按 action 分发到搜索`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = """{"results":[]}"""))
        val tool = NotionUnifiedTool(http = http, token = "secret-token")
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(
            buildJsonObject {
                put("action", "search")
                put("query", "设计")
            },
            ctx,
        )

        assertFalse("搜索不应失败：${result.content}", result.isError)
        assertTrue("应请求 search 接口", http.requests.single().url.endsWith("/search"))
    }

    @Test
    fun `notion 工具没传 action 时按参数推断`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = """{"results":[]}"""))
        val tool = NotionUnifiedTool(http = http, token = "secret-token")
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("query", "只有关键词") }, ctx)

        assertFalse(result.isError)
        assertTrue("应推断成 search", http.requests.single().url.endsWith("/search"))
    }

    @Test
    fun `notion create 会先请求用户确认`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = """{"id":"abc","url":"https://notion.so/x"}"""))
        val tool = NotionUnifiedTool(http = http, token = "secret-token")
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(
            buildJsonObject {
                put("action", "create")
                put("title", "会议记录")
            },
            ctx,
        )

        assertFalse("确认后应执行成功：${result.content}", result.isError)
        assertEquals("写操作应请求一次确认", 1, ctx.approvals.size)
        assertTrue("应请求创建页面接口", http.requests.single().url.endsWith("/pages"))
    }

    @Test
    fun `notion create 被拒绝时不发请求`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = "{}"))
        val tool = NotionUnifiedTool(http = http, token = "secret-token")
        val ctx = FakeToolContext(http = http, approve = false)

        val result = tool.execute(
            buildJsonObject {
                put("action", "create")
                put("title", "会议记录")
            },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("拒绝"))
        assertTrue("被拒绝后不应发请求", http.requests.isEmpty())
    }

    // ------------------------------------------------------------------ 其它内置工具

    @Test
    fun `HttpRequestTool 只允许 http 协议`() = runTest {
        val tool = HttpRequestTool(FakeHttp())
        val ctx = FakeToolContext()

        val result = tool.execute(buildJsonObject { put("url", "file:///etc/passwd") }, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("仅支持 http/https"))
    }

    @Test
    fun `HttpRequestTool 正常返回状态码与响应体`() = runTest {
        val http = FakeHttp(HttpResponse(200, body = "hello"))
        val tool = HttpRequestTool(http)
        val ctx = FakeToolContext(http = http)

        val result = tool.execute(buildJsonObject { put("url", "https://example.com/api") }, ctx)

        assertFalse(result.isError)
        assertTrue(result.content.contains("状态码: 200"))
        assertTrue(result.content.contains("hello"))
    }

    @Test
    fun `内置工具的错误结果都带 isError 标记`() {
        val error: ToolResult = ToolResult.error("出错了")
        assertTrue(error.isError)
        assertEquals("出错了", error.content)
    }
}
