package com.mynok.myagent.core.tools.builtin

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolApproval
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Notion API 的公共部分：鉴权、请求、错误处理。
 *
 * token 由构造参数传入（不放进 [ToolContext]），null 时所有子工具都会返回
 * 「未配置 Notion token」而不是抛异常。
 */
abstract class NotionTool(
    protected val http: Http? = null,
    protected val token: String? = null,
    protected val secrets: ToolSecrets? = null,
) : Tool {

    /** 现取 token：用户在「工具 → 集成密钥」里改完立即生效。 */
    protected suspend fun resolveToken(): String? =
        secrets?.get(ToolSecretKeys.NOTION)?.trim()?.takeIf { it.isNotEmpty() }
            ?: token?.trim()?.takeIf { it.isNotEmpty() }

    /** 调用 Notion API。 */
    protected suspend fun call(
        ctx: ToolContext,
        method: String,
        url: String,
        body: String? = null,
    ): NotionApiResult {
        val key = resolveToken()
            ?: return NotionApiResult.Err(
                "未配置 Notion token，请在「工具 → 集成密钥」里填写 Notion Integration Token。",
            )

        val client = http ?: ctx.http
        ctx.log("notion $method $url")

        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    method = method,
                    headers = mapOf(
                        "Authorization" to "Bearer $key",
                        "Notion-Version" to NOTION_VERSION,
                        "Content-Type" to "application/json; charset=utf-8",
                    ),
                    body = body,
                    contentType = if (body != null) "application/json; charset=utf-8" else null,
                    timeoutSeconds = 45,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return NotionApiResult.Err("Notion 请求失败：${e.message ?: e::class.simpleName}")
        }

        if (!response.isSuccessful) {
            return NotionApiResult.Err(
                "Notion 请求失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val json = runCatching { MyAgentJson.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            ?: return NotionApiResult.Err(
                "Notion 返回了无法解析的 JSON：${HttpUtils.summarize(response.body)}",
            )
        return NotionApiResult.Ok(json)
    }

    companion object {
        const val API_BASE = "https://api.notion.com/v1"
        const val NOTION_VERSION = "2022-06-28"
    }
}

/** Notion API 调用结果。 */
sealed interface NotionApiResult {

    /** 成功，附带解析后的 JSON。 */
    data class Ok(val json: JsonObject) : NotionApiResult

    /** 失败，附带可直接回灌给模型的错误说明。 */
    data class Err(val message: String) : NotionApiResult
}

/** Notion API 调用失败。 */
class NotionApiException(message: String) : Exception(message)

/**
 * 在 Notion 中搜索页面 / 数据库。
 *
 * 只读，风险 READ_ONLY。
 */
class NotionSearchTool(
    http: Http? = null,
    token: String? = null,
    secrets: ToolSecrets? = null,
) : NotionTool(http, token, secrets) {

    override val spec: ToolSpec = ToolSpec(
        name = "notion_search",
        description = "在 Notion 中搜索页面或数据库。参数：query（可选，留空表示全部）、" +
            "limit（可选，默认 10，最大 50）。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "搜索关键词，留空表示列出全部可访问的页面")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "返回条数，默认 10，最大 50")
                })
            })
        },
    )

    override val risk: ToolRisk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.stringArg("query", "q", "keyword")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (args.intArg("limit", "count", "page_size") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val body = buildJsonObject {
            put("page_size", JsonPrimitive(limit))
            if (query != null) put("query", JsonPrimitive(query))
        }.toString()

        return when (val result = call(ctx, "POST", "${API_BASE}/search", body)) {
            is NotionApiResult.Err -> ToolResult.error(result.message)
            is NotionApiResult.Ok -> {
                val results = result.json["results"] as? JsonArray
                    ?: return ToolResult.ok("没有找到结果。")
                if (results.isEmpty()) {
                    return ToolResult.ok("没有找到${query?.let { "与「$it」相关的" } ?: ""}结果。")
                }

                val lines = mutableListOf<String>()
                results.take(limit).forEachIndexed { index, element ->
                    val obj = element as? JsonObject ?: return@forEachIndexed
                    val kind = when (obj.stringField("object")) {
                        "database" -> "数据库"
                        else -> "页面"
                    }
                    lines += "${index + 1}. [$kind] ${titleOf(obj).ifBlank { "(无标题)" }}"
                    obj.stringField("id")?.let { lines += "   id: $it" }
                    obj.stringField("url")?.let { lines += "   url: $it" }
                }
                ToolResult.ok(lines.joinToString("\n"))
            }
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val MAX_LIMIT = 50
    }
}

/**
 * 读取一个 Notion 页面的内容，并展开成可读文本。
 *
 * 只读，风险 READ_ONLY。
 */
class NotionFetchPageTool(
    http: Http? = null,
    token: String? = null,
    secrets: ToolSecrets? = null,
) : NotionTool(http, token, secrets) {

    override val spec: ToolSpec = ToolSpec(
        name = "notion_fetch_page",
        description = "读取 Notion 页面的内容块并转成可读文本。参数：page_id（必填，" +
            "支持裸 id 或 Notion 页面链接）。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("page_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Notion 页面 id 或页面链接")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("page_id")) })
        },
    )

    override val risk: ToolRisk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args.stringArg("page_id", "pageId", "id", "block_id")?.trim()
            ?: return ToolResult.error("缺少参数 page_id（Notion 页面 id）")
        val pageId = normalizeNotionId(raw)
            ?: return ToolResult.error("page_id 格式不正确：$raw")

        // 先确认 token 配没配，避免把鉴权失败误报成「页面是空的」
        if (resolveToken() == null) {
            return ToolResult.error(
                "未配置 Notion token：请在「工具 → 集成密钥」里填写后再试。",
            )
        }

        val title = fetchTitle(ctx, pageId)
        val blocksResult = fetchBlocks(ctx, pageId)
        if (blocksResult.isFailure) {
            return ToolResult.error(
                "读取 Notion 页面失败：${blocksResult.exceptionOrNull()?.message ?: "未知错误"}",
            )
        }
        val blocks = blocksResult.getOrDefault(emptyList())
        if (blocks.isEmpty()) {
            val head = if (title.isNotBlank()) "标题: $title\n\n" else ""
            return ToolResult.ok("${head}（页面没有内容块）")
        }

        val out = StringBuilder()
        renderBlocks(ctx, blocks, depth = 0, out = out)
        val text = out.toString().trim()
        if (text.isEmpty()) {
            val head = if (title.isNotBlank()) "标题: $title\n\n" else ""
            return ToolResult.ok("${head}（页面没有可读的文本内容）")
        }

        return ToolResult.ok(if (title.isNotBlank()) "标题: $title\n\n$text" else text)
    }

    /** 尽力而为地取页面标题；失败不阻塞正文读取。 */
    private suspend fun fetchTitle(ctx: ToolContext, pageId: String): String {
        val result = call(ctx, "GET", "${API_BASE}/pages/$pageId")
        if (result !is NotionApiResult.Ok) return ""
        return titleOf(result.json)
    }

    /** 拉取一个 block 的子块（带分页）。 */
    private suspend fun fetchBlocks(ctx: ToolContext, blockId: String): Result<List<JsonObject>> {
        val blocks = mutableListOf<JsonObject>()
        var cursor: String? = null
        var page = 0

        while (page < MAX_BLOCK_PAGES) {
            val url = buildString {
                append("${API_BASE}/blocks/$blockId/children?page_size=$PAGE_SIZE")
                if (cursor != null) {
                    append("&start_cursor=")
                    append(URLEncoder.encode(cursor, "UTF-8"))
                }
            }
            val result = call(ctx, "GET", url)
            if (result is NotionApiResult.Err) {
                return Result.failure(NotionApiException(result.message))
            }
            val ok = result as NotionApiResult.Ok
            val results = ok.json["results"] as? JsonArray ?: break
            results.forEach { element ->
                (element as? JsonObject)?.let { blocks += it }
            }
            val hasMore = ok.json.booleanField("has_more") == true
            cursor = ok.json.stringField("next_cursor")
            if (!hasMore || cursor.isNullOrEmpty()) break
            page++
        }
        return Result.success(blocks)
    }

    /** 递归展开 block 树。 */
    private suspend fun renderBlocks(
        ctx: ToolContext,
        blocks: List<JsonObject>,
        depth: Int,
        out: StringBuilder,
    ) {
        for (block in blocks) {
            val type = block.stringField("type") ?: continue
            val payload = block.jsonObject(type)
            val text = richTextToPlain(payload?.jsonArray("rich_text"))

            when (type) {
                "paragraph" -> out.appendLine(text)
                "heading_1" -> out.appendLine("# $text")
                "heading_2" -> out.appendLine("## $text")
                "heading_3" -> out.appendLine("### $text")
                "bulleted_list_item" -> out.appendLine("- $text")
                "numbered_list_item" -> out.appendLine("1. $text")
                "to_do" -> {
                    val checked = payload?.booleanField("checked") == true
                    out.appendLine(if (checked) "- [x] $text" else "- [ ] $text")
                }

                "toggle" -> {
                    if (text.isNotBlank()) out.appendLine(text)
                }

                "code" -> {
                    val language = payload?.stringField("language").orEmpty()
                    out.appendLine("```$language")
                    out.appendLine(text)
                    out.appendLine("```")
                }

                "quote" -> out.appendLine("> $text")
                "callout" -> {
                    val icon = payload?.jsonObject("icon")?.stringField("emoji").orEmpty()
                    out.appendLine("> ${icon.ifBlank { "💡" }} $text")
                }

                "child_page" -> out.appendLine("[子页面] ${payload?.stringField("title").orEmpty()}")
                "child_database" -> out.appendLine("[子数据库] ${payload?.stringField("title").orEmpty()}")
                "divider" -> out.appendLine("---")
                "image" -> out.appendLine("[图片] ${fileUrl(payload)}")
                "video" -> out.appendLine("[视频] ${fileUrl(payload)}")
                "file", "pdf" -> out.appendLine("[文件] ${fileUrl(payload)}")
                "bookmark", "embed" -> out.appendLine("[链接] ${payload?.stringField("url").orEmpty()}")
                "equation" -> out.appendLine(payload?.stringField("expression").orEmpty())
                "table" -> out.appendLine("[表格]")
                "table_row" -> {
                    val cells = payload?.jsonArray("cells")?.map { richTextToPlain(it as? JsonArray) }
                        .orEmpty()
                    out.appendLine(cells.joinToString(" | ") { "| $it " })
                }

                "column_list", "column" -> Unit
                "unsupported" -> out.appendLine("[不支持的内容]")
                else -> {
                    if (text.isNotBlank()) out.appendLine(text) else out.appendLine("[$type]")
                }
            }

            val childId = block.stringField("id")
            if (block.booleanField("has_children") == true && !childId.isNullOrEmpty() && depth < MAX_DEPTH) {
                val children = fetchBlocks(ctx, childId).getOrDefault(emptyList())
                if (children.isNotEmpty()) {
                    renderBlocks(ctx, children, depth + 1, out)
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_BLOCK_PAGES = 5
        private const val MAX_DEPTH = 5
    }
}

/**
 * 在 Notion 中新建页面。
 *
 * 写操作，风险 WRITE。
 */
class NotionCreatePageTool(
    http: Http? = null,
    token: String? = null,
    secrets: ToolSecrets? = null,
) : NotionTool(http, token, secrets) {

    override val spec: ToolSpec = ToolSpec(
        name = "notion_create_page",
        description = "在 Notion 中创建一个新页面。参数：title（必填）、" +
            "parent_page_id（可选，父页面 id，留空则建在工作区根下）、body（可选，正文文本，每行一段）。" +
            "这是写操作，会实际修改 Notion 内容。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "页面标题")
                })
                put("parent_page_id", buildJsonObject {
                    put("type", "string")
                    put("description", "父页面 id 或页面链接，留空则创建在工作区根下")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "页面正文，每行作为一个段落")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("title")) })
        },
    )

    override val risk: ToolRisk = ToolRisk.WRITE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.stringArg("title", "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("缺少参数 title（页面标题）")
        val body = args.stringArg("body", "content", "text")
        val parentRaw = args.stringArg("parent_page_id", "parent_id", "parentId")?.trim()

        val parent = if (parentRaw.isNullOrEmpty()) {
            buildJsonObject { put("type", JsonPrimitive("workspace")) }
        } else {
            val parentId = normalizeNotionId(parentRaw)
                ?: return ToolResult.error("parent_page_id 格式不正确：$parentRaw")
            buildJsonObject {
                put("type", JsonPrimitive("page_id"))
                put("page_id", JsonPrimitive(parentId))
            }
        }

        val payload = buildJsonObject {
            put("parent", parent)
            put("properties", buildJsonObject {
                put("title", buildJsonObject {
                    put("title", buildJsonArray { add(richTextObject(title)) })
                })
            })
            if (!body.isNullOrBlank()) {
                put("children", bodyToBlocks(body))
            }
        }.toString()

        return when (val result = call(ctx, "POST", "${API_BASE}/pages", payload)) {
            is NotionApiResult.Err -> ToolResult.error(result.message)
            is NotionApiResult.Ok -> {
                val id = result.json.stringField("id").orEmpty()
                val url = result.json.stringField("url").orEmpty()
                buildString {
                    appendLine("已在 Notion 创建页面。")
                    appendLine("标题: $title")
                    if (id.isNotEmpty()) appendLine("id: $id")
                    if (url.isNotEmpty()) appendLine("url: $url")
                }.trimEnd().let { ToolResult.ok(it) }
            }
        }
    }

    /** 正文按行拆成段落块。 */
    private fun bodyToBlocks(body: String): JsonArray = buildJsonArray {
        body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_BODY_BLOCKS)
            .forEach { line ->
                add(
                    buildJsonObject {
                        put("object", JsonPrimitive("block"))
                        put("type", JsonPrimitive("paragraph"))
                        put("paragraph", buildJsonObject {
                            put("rich_text", buildJsonArray { add(richTextObject(line)) })
                        })
                    },
                )
            }
    }

    companion object {
        private const val MAX_BODY_BLOCKS = 100
    }
}

/**
 * 合并后的 Notion 工具。
 *
 * 原来是 notion_search / notion_fetch_page / notion_create_page 三个工具，
 * 工具一多模型就容易选错；现在统一成 `notion` + `action` 参数。
 *
 * 读操作是 READ_ONLY，不会额外弹确认；`create` 是写操作，执行前单独请求确认
 * （工具的静态 risk 保持 READ_ONLY，避免搜个页面也要点一次弹窗）。
 */
class NotionUnifiedTool(
    http: Http? = null,
    token: String? = null,
    secrets: ToolSecrets? = null,
) : Tool {

    private val search = NotionSearchTool(http, token, secrets)
    private val readPage = NotionFetchPageTool(http, token, secrets)
    private val createPage = NotionCreatePageTool(http, token, secrets)

    override val spec: ToolSpec = ToolSpec(
        name = "notion",
        description = "操作 Notion。action=search 搜索页面/数据库（query、limit）；" +
            "action=read 读取页面正文（page_id，支持页面链接）；" +
            "action=create 新建页面（title、parent_page_id 可选、body 可选，写操作，会要求确认）。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "search 搜索 / read 读页面 / create 新建页面")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("search"))
                        add(JsonPrimitive("read"))
                        add(JsonPrimitive("create"))
                    })
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search 用：搜索关键词，留空列出全部可访问页面")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "search 用：返回条数，默认 10，最大 50")
                })
                put("page_id", buildJsonObject {
                    put("type", "string")
                    put("description", "read 用：页面 id 或 Notion 页面链接")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "create 用：页面标题")
                })
                put("parent_page_id", buildJsonObject {
                    put("type", "string")
                    put("description", "create 用：父页面 id 或链接，留空建在工作区根下")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "create 用：正文，每行一个段落")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        },
    )

    override val risk: ToolRisk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.stringArg("action", "op", "operation")?.trim()?.lowercase()
            ?: inferAction(args)
        return when (action) {
            "search", "find", "list", "query" -> search.execute(args, ctx)
            "read", "fetch", "get", "page" -> readPage.execute(args, ctx)
            "create", "new", "add", "write" -> {
                val approved = ctx.approver.request(
                    ToolApproval(
                        toolName = spec.name,
                        reason = "在 Notion 中新建页面「${args.stringArg("title").orEmpty()}」",
                        risk = ToolRisk.WRITE,
                        detail = args.toString().take(500),
                    ),
                )
                if (!approved) {
                    ToolResult.error("用户拒绝执行 Notion 写操作（notion create）。")
                } else {
                    createPage.execute(args, ctx)
                }
            }

            else -> ToolResult.error(
                "未知的 action：$action（可选 search / read / create）",
            )
        }
    }

    /** 模型偶尔忘了传 action，按参数猜一个，比直接报错好用。 */
    private fun inferAction(args: JsonObject): String = when {
        args.stringArg("page_id", "pageId", "id", "block_id") != null -> "read"
        args.stringArg("title", "name") != null -> "create"
        else -> "search"
    }
}

// ---------------- 内部辅助 ----------------

/** Notion id 归一化：支持裸 id、带横线的 uuid、页面链接。 */
internal fun normalizeNotionId(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    text = text.substringBefore('?').substringBefore('#')
    if (text.contains("notion.so/") || text.contains("notion.site/")) {
        text = text.substringAfterLast('/')
    }
    val compact = text.replace("-", "").replace("{", "").replace("}", "").trim()
    if (compact.length < 32) return null
    val candidate = compact.takeLast(32)
    if (!candidate.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return candidate.substring(0, 8) + "-" +
        candidate.substring(8, 12) + "-" +
        candidate.substring(12, 16) + "-" +
        candidate.substring(16, 20) + "-" +
        candidate.substring(20, 32)
}

/** 取页面 / 数据库的标题。 */
internal fun titleOf(obj: JsonObject): String {
    val direct = richTextToPlain(obj.jsonArray("title"))
    if (direct.isNotBlank()) return direct
    val properties = obj.jsonObject("properties") ?: return ""
    for ((_, value) in properties) {
        val property = value as? JsonObject ?: continue
        if (property.stringField("type") != "title") continue
        val text = richTextToPlain(property.jsonArray("title"))
        if (text.isNotBlank()) return text
    }
    return ""
}

/** rich_text 数组转成带轻量标记的纯文本。 */
internal fun richTextToPlain(items: JsonArray?): String {
    if (items == null) return ""
    val out = StringBuilder()
    for (element in items) {
        val obj = element as? JsonObject ?: continue
        var text = obj.stringField("plain_text")
            ?: obj.jsonObject("text")?.stringField("content")
            ?: continue
        val annotations = obj.jsonObject("annotations")
        if (annotations?.booleanField("code") == true) text = "`$text`"
        if (annotations?.booleanField("bold") == true) text = "**$text**"
        if (annotations?.booleanField("italic") == true) text = "_${text}_"
        val link = obj.stringField("href")
            ?: obj.jsonObject("text")?.jsonObject("link")?.stringField("url")
        if (!link.isNullOrBlank()) text += " ($link)"
        out.append(text)
    }
    return out.toString()
}

/** 一个 rich_text 片段。 */
private fun richTextObject(content: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("text"))
    put("text", buildJsonObject { put("content", JsonPrimitive(content)) })
}

/** 图片 / 视频 / 文件的地址。 */
private fun fileUrl(payload: JsonObject?): String {
    if (payload == null) return ""
    payload.stringField("url")?.let { return it }
    val file = payload.jsonObject("file") ?: payload.jsonObject("external") ?: return ""
    return file.stringField("url").orEmpty()
}

// ---------------- JSON 小助手 ----------------

private fun JsonObject.jsonObject(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.jsonArray(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull
