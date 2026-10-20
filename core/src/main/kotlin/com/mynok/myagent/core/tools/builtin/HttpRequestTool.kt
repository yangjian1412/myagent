package com.mynok.myagent.core.tools.builtin

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 通用 REST 调用工具。
 *
 * 读方法（GET/HEAD/OPTIONS）风险是 READ_ONLY，写方法（POST/PUT/PATCH/DELETE）是 WRITE。
 * [risk] 会随最近一次调用的方法动态变化，便于 UI 展示真实风险。
 */
class HttpRequestTool(
    private val http: Http? = null,
) : Tool {

    @Volatile
    private var currentRisk: ToolRisk = ToolRisk.READ_ONLY

    override val risk: ToolRisk get() = currentRisk

    override val spec: ToolSpec = ToolSpec(
        name = "http_request",
        description = "发起一个 HTTP 请求并返回状态码与响应体。" +
            "参数：method（GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS，默认 GET）、url（必填）、" +
            "body（可选请求体字符串或 JSON 对象）、headers（可选的请求头对象）。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("method", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "HTTP 方法：GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS，默认 GET",
                    )
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("GET"))
                        add(JsonPrimitive("POST"))
                        add(JsonPrimitive("PUT"))
                        add(JsonPrimitive("PATCH"))
                        add(JsonPrimitive("DELETE"))
                        add(JsonPrimitive("HEAD"))
                        add(JsonPrimitive("OPTIONS"))
                    })
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "要请求的地址，必须是 http/https 链接")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "可选的请求体内容（纯文本或 JSON）")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "可选的请求头，键为头名称，值为字符串")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("url")) })
        },
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.stringArg("url", "endpoint", "link")?.trim()
            ?: return ToolResult.error("缺少参数 url（要请求的地址）")
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ToolResult.error("仅支持 http/https 协议，收到：$url")
        }

        val method = (args.stringArg("method", "verb") ?: "GET").trim().uppercase()
        if (method !in ALLOWED_METHODS) {
            return ToolResult.error(
                "不支持的 HTTP 方法：$method（可选：${ALLOWED_METHODS.joinToString(" / ")}）",
            )
        }

        val body = readBody(args)
        val headers = readHeaders(args)
        val contentType = headers.keys
            .firstOrNull { it.equals("Content-Type", ignoreCase = true) }
            ?.let { headers.remove(it) }

        currentRisk = if (method in WRITE_METHODS) ToolRisk.WRITE else ToolRisk.READ_ONLY

        val client = http ?: ctx.http
        ctx.log("http_request $method $url")

        val response = try {
            client.execute(
                HttpRequest(
                    url = url,
                    method = method,
                    headers = headers,
                    body = body,
                    contentType = contentType,
                    timeoutSeconds = 60,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.error("HTTP 请求失败：${e.message ?: e::class.simpleName}")
        }

        if (!response.isSuccessful) {
            return ToolResult.error(
                "HTTP 请求失败：状态码 ${response.code}\n${HttpUtils.summarize(response.body)}",
            )
        }

        val text = response.body.ifBlank { "（响应体为空）" }
        val clipped = if (text.length > MAX_BODY_CHARS) {
            text.take(MAX_BODY_CHARS) + "\n…（响应体过长，已截断）"
        } else {
            text
        }
        return ToolResult.ok("状态码: ${response.code}\n\n$clipped")
    }

    /** body 允许是字符串，也允许是 JSON 对象/数组（直接序列化）。 */
    private fun readBody(args: JsonObject): String? = when (val element = args["body"]) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject, is JsonArray -> element.toString()
    }

    private fun readHeaders(args: JsonObject): MutableMap<String, String> {
        val headers = linkedMapOf<String, String>()
        val obj = args["headers"] as? JsonObject ?: return headers
        obj.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            headers[key] = text
        }
        return headers
    }

    companion object {
        private const val MAX_BODY_CHARS = 8_000

        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

/** 内置工具共用的 HTTP 辅助。 */
internal object HttpUtils {

    /** 响应体摘要：压缩空白并截断，便于放进错误信息里。 */
    fun summarize(body: String, max: Int = 400): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return "（响应体为空）"
        return if (flat.length > max) flat.take(max) + "…" else flat
    }
}
