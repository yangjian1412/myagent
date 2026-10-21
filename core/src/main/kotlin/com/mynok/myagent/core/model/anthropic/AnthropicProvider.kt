package com.mynok.myagent.core.model.anthropic

import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.ContentParts.asTextContent
import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelException
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelProvider
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ModelResult
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.SseEvent
import com.mynok.myagent.core.model.SseParser
import com.mynok.myagent.core.model.ToolCall
import com.mynok.myagent.core.model.ToolCallAccumulator
import com.mynok.myagent.core.model.streamSseEvents
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API 原生协议适配器。
 *
 * 与 OpenAI 兼容协议的差异：
 * - 端点是 `{baseUrl}/v1/messages`，鉴权头是 `x-api-key`，另需 `anthropic-version`
 * - 系统提示是顶层 `system` 字段，不是消息
 * - 消息 content 是「内容块」数组：text / image / tool_use / tool_result
 * - 工具声明用 `input_schema` 描述参数
 * - 工具结果放在 role=user 的消息里，块类型是 tool_result
 */
class AnthropicProvider(
    override val profile: ModelProfile,
    private val http: Http,
    private val apiKey: String? = null,
) : ModelProvider {

    private val endpoint: String
        get() {
            val base = profile.baseUrl.trimEnd('/')
            return when {
                base.endsWith("/v1/messages") -> base
                base.endsWith("/v1") -> "$base/messages"
                else -> "$base/v1/messages"
            }
        }

    override suspend fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        try {
            val body = buildRequestBody(request)
            val headers = buildHeaders(streaming = true)

            var finish = FinishReason.STOP
            var promptTokens = 0
            var completionTokens = 0
            var hasUsage = false

            // 每条 SSE 立刻解析并 emit，保证逐字输出
            suspend fun handle(event: SseEvent) {
                val data = event.data.trim()
                if (data.isEmpty() || data == "[DONE]") return
                val json = runCatching { MyAgentJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

                json["error"]?.let { err ->
                    val msg = (err as? JsonObject)?.str("message") ?: err.asString() ?: "未知错误"
                    throw ModelException("模型服务返回错误：$msg", rawBody = data)
                }

                val type = json.str("type") ?: event.name ?: return
                when (type) {
                    "message_start" -> {
                        val usage = (json["message"] as? JsonObject)?.get("usage") as? JsonObject
                        usage?.int("input_tokens")?.let { promptTokens = it; hasUsage = true }
                        usage?.int("output_tokens")?.let { completionTokens = it; hasUsage = true }
                    }

                    "content_block_start" -> {
                        val index = json.int("index") ?: 0
                        val block = json["content_block"] as? JsonObject
                        if (block?.str("type") == "tool_use") {
                            emit(
                                ModelEvent.ToolCallDelta(
                                    index = index,
                                    id = block.str("id"),
                                    name = block.str("name"),
                                    argumentsDelta = null,
                                ),
                            )
                        }
                    }

                    "content_block_delta" -> {
                        val index = json.int("index") ?: 0
                        val delta = json["delta"] as? JsonObject
                        val deltaType = delta?.str("type")
                        if (deltaType == "text_delta") {
                            val text = delta?.str("text").orEmpty()
                            if (text.isNotEmpty()) emit(ModelEvent.TextDelta(text))
                        } else if (deltaType == "thinking_delta") {
                            val thinking = delta?.str("thinking").orEmpty()
                            if (thinking.isNotEmpty()) emit(ModelEvent.ReasoningDelta(thinking))
                        } else if (deltaType == "input_json_delta") {
                            val partial = delta?.str("partial_json").orEmpty()
                            if (partial.isNotEmpty()) {
                                emit(ModelEvent.ToolCallDelta(index = index, argumentsDelta = partial))
                            }
                        }
                    }

                    "message_delta" -> {
                        val delta = json["delta"] as? JsonObject
                        delta?.str("stop_reason")?.let { finish = parseFinish(it) }
                        val usage = json["usage"] as? JsonObject
                        usage?.int("input_tokens")?.let { promptTokens = it; hasUsage = true }
                        usage?.int("output_tokens")?.let { completionTokens = it; hasUsage = true }
                    }

                    "content_block_stop", "message_stop", "ping" -> Unit

                    else -> Unit
                }
            }

            val response = http.streamSseEvents(
                HttpRequest(
                    url = endpoint,
                    method = "POST",
                    headers = headers,
                    body = body.toString(),
                    contentType = "application/json; charset=utf-8",
                ),
            ) { handle(it) }

            if (response.code !in 200..299) {
                throw ModelException(
                    message = "模型服务返回 HTTP ${response.code}：${response.body.take(500)}",
                    statusCode = response.code,
                    rawBody = response.body,
                )
            }

            if (hasUsage) {
                emit(ModelEvent.Usage(promptTokens, completionTokens, promptTokens + completionTokens))
            }
            emit(ModelEvent.Completed(finish))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 流必须以 Completed 或 Failed 收尾，否则 agent 循环会挂住
            emit(ModelEvent.Failed(e))
        }
    }

    override suspend fun complete(request: ModelRequest): ModelResult {
        val body = buildRequestBody(request.copy(stream = false))
        val response = http.execute(
            HttpRequest(
                url = endpoint,
                method = "POST",
                headers = buildHeaders(streaming = false),
                body = body.toString(),
                contentType = "application/json; charset=utf-8",
            ),
        )
        if (!response.isSuccessful) {
            throw ModelException(
                message = "模型服务返回 HTTP ${response.code}：${response.body.take(500)}",
                statusCode = response.code,
                rawBody = response.body,
            )
        }
        val raw = response.body.trim()
        // 个别中转服务即使 stream=false 也回 SSE，这里统一走流式解析
        if (raw.startsWith("data:") || raw.contains("\ndata:")) {
            return resultOf(parseEvents(response.body))
        }
        val json = runCatching { MyAgentJson.parseToJsonElement(response.body).jsonObject }
            .getOrElse { throw ModelException("模型服务返回的不是合法 JSON", rawBody = response.body) }
        return parseNonStreaming(json)
    }

    // ------------------------------------------------------------------ 请求

    private fun buildHeaders(streaming: Boolean): Map<String, String> {
        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json; charset=utf-8",
            "anthropic-version" to ANTHROPIC_VERSION,
        )
        if (streaming) headers["Accept"] = "text/event-stream"
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-api-key"] = it }
        headers += profile.headers
        return headers
    }

    internal fun buildRequestBody(request: ModelRequest): JsonObject = buildJsonObject {
        put("model", profile.modelId)
        // Anthropic 要求 max_tokens 严格大于思考预算，否则直接 400
        val effort = request.reasoningEffort
        val requestedMax = request.maxTokens ?: profile.maxOutputTokens
        val maxTokens = if (effort.isOn) maxOf(requestedMax, effort.budgetTokens + 1_024) else requestedMax
        put("max_tokens", maxTokens)
        put("stream", request.stream)

        if (effort.isOn) {
            put(
                "thinking",
                buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", effort.budgetTokens)
                },
            )
        }

        buildSystem(request)?.let { put("system", it) }
        put("messages", buildMessages(request.messages))

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.parameters)
                            },
                        )
                    }
                },
            )
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }

        (request.temperature ?: profile.temperature)?.let { put("temperature", it) }
        (request.topP ?: profile.topP)?.let { put("top_p", it) }

        for ((k, v) in request.extra) {
            put(k, v)
        }
        for ((k, v) in profile.extraBody) {
            put(k, v)
        }
    }

    /** 系统提示走顶层字段；消息里带的 system 消息也并进来。 */
    private fun buildSystem(request: ModelRequest): String? {
        val inline = request.messages
            .filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        val main = request.system?.trim().orEmpty()
        val merged = listOf(main, inline).filter { it.isNotBlank() }.joinToString("\n\n")
        return merged.ifBlank { null }
    }

    /** 转成 Anthropic 的「角色 + 内容块」消息数组（连续同角色会合并）。 */
    private fun buildMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var role: String? = null
        var blocks: MutableList<JsonElement> = mutableListOf()

        fun flush() {
            val current = role
            if (current != null && blocks.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("role", current)
                        put("content", JsonArray(blocks.toList()))
                    },
                )
            }
            role = null
            blocks = mutableListOf()
        }

        messages.forEach { message ->
            when (message.role) {
                // system 已经是顶层字段
                Role.SYSTEM -> Unit

                // 工具结果放在 role=user 的消息里
                Role.TOOL -> {
                    if (role != "user") flush()
                    role = "user"
                    blocks += toolResultBlock(message)
                }

                Role.USER, Role.ASSISTANT -> {
                    val wireRole = if (message.role == Role.ASSISTANT) "assistant" else "user"
                    if (role != null && role != wireRole) flush()
                    role = wireRole
                    blocks += encodeBlocks(message)
                }
            }
        }
        flush()
    }

    /** 内容块数组：文本 / 图片 / 助手发起的 tool_use。 */
    private fun encodeBlocks(message: ChatMessage): List<JsonElement> = buildList {
        message.parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> if (part.text.isNotEmpty()) add(textBlock(part.text))

                is ContentPart.Image -> add(
                    buildJsonObject {
                        put("type", "image")
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "base64")
                                put("media_type", part.mimeType)
                                put("data", part.base64)
                            },
                        )
                    },
                )

                is ContentPart.File -> {
                    // 文本类附件转成文本块：所有模型都能读，也更省 token
                    val asText = part.asTextContent()
                    if (asText != null) {
                        add(textBlock(asText))
                    } else {
                        add(
                            buildJsonObject {
                                put("type", "document")
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", part.mimeType)
                                        put("data", part.base64)
                                    },
                                )
                                // 文件名对模型判断内容有帮助（Anthropic 会把它当 context）
                                put("title", part.name)
                            },
                        )
                    }
                }
            }
        }
        if (message.role == Role.ASSISTANT) {
            message.toolCalls.forEach { add(toolUseBlock(it)) }
        }
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** Anthropic 的 tool_use.input 是对象；内部 arguments 是字符串，这里解析回对象。 */
    private fun toolUseBlock(call: ToolCall): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", call.id)
        put("name", call.name)
        put("input", parseInputObject(call.arguments))
    }

    private fun toolResultBlock(message: ChatMessage): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", message.toolCallId.orEmpty())
        put("content", message.text)
    }

    private fun parseInputObject(arguments: String): JsonObject =
        runCatching { MyAgentJson.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    // ------------------------------------------------------------------ 解析

    /** 把一整段 SSE 解析成统一事件序列（非流式复用）。 */
    private fun parseEvents(raw: String): List<ModelEvent> {
        val parser = SseParser()
        val events = mutableListOf<SseEvent>()
        events += parser.onChunk(raw)
        events += parser.finish()

        val out = mutableListOf<ModelEvent>()
        var finish = FinishReason.STOP
        var promptTokens = 0
        var completionTokens = 0
        var hasUsage = false

        for (event in events) {
            val data = event.data.trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val json = runCatching { MyAgentJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

            json["error"]?.let { err ->
                val msg = (err as? JsonObject)?.str("message") ?: err.asString() ?: "未知错误"
                throw ModelException("模型服务返回错误：$msg", rawBody = data)
            }

            val type = json.str("type") ?: event.name ?: continue
            when (type) {
                "message_start" -> {
                    val usage = (json["message"] as? JsonObject)?.get("usage") as? JsonObject
                    usage?.int("input_tokens")?.let { promptTokens = it; hasUsage = true }
                    usage?.int("output_tokens")?.let { completionTokens = it; hasUsage = true }
                }

                "content_block_start" -> {
                    val index = json.int("index") ?: 0
                    val block = json["content_block"] as? JsonObject
                    if (block?.str("type") == "tool_use") {
                        out += ModelEvent.ToolCallDelta(
                            index = index,
                            id = block.str("id"),
                            name = block.str("name"),
                            argumentsDelta = null,
                        )
                    }
                }

                "content_block_delta" -> {
                    val index = json.int("index") ?: 0
                    val delta = json["delta"] as? JsonObject
                    val deltaType = delta?.str("type")
                    if (deltaType == "text_delta") {
                        val text = delta?.str("text").orEmpty()
                        if (text.isNotEmpty()) out += ModelEvent.TextDelta(text)
                    } else if (deltaType == "thinking_delta") {
                        val thinking = delta?.str("thinking").orEmpty()
                        if (thinking.isNotEmpty()) out += ModelEvent.ReasoningDelta(thinking)
                    } else if (deltaType == "input_json_delta") {
                        val partial = delta?.str("partial_json").orEmpty()
                        if (partial.isNotEmpty()) {
                            out += ModelEvent.ToolCallDelta(index = index, argumentsDelta = partial)
                        }
                    }
                }

                "message_delta" -> {
                    val delta = json["delta"] as? JsonObject
                    delta?.str("stop_reason")?.let { finish = parseFinish(it) }
                    val usage = json["usage"] as? JsonObject
                    usage?.int("input_tokens")?.let { promptTokens = it; hasUsage = true }
                    usage?.int("output_tokens")?.let { completionTokens = it; hasUsage = true }
                }

                "content_block_stop", "message_stop", "ping" -> Unit

                else -> Unit
            }
        }

        if (hasUsage) {
            out += ModelEvent.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
        }
        out += ModelEvent.Completed(finish)
        return out
    }

    private fun parseNonStreaming(json: JsonObject): ModelResult {
        json["error"]?.let { err ->
            val msg = (err as? JsonObject)?.str("message") ?: err.asString() ?: "未知错误"
            throw ModelException("模型服务返回错误：$msg", rawBody = json.toString())
        }

        val blocks = json["content"] as? JsonArray
            ?: throw ModelException("模型服务返回的响应缺少 content", rawBody = json.toString())

        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        blocks.forEachIndexed { idx, element ->
            val block = element as? JsonObject ?: return@forEachIndexed
            val type = block.str("type")
            if (type == "text") {
                block.str("text")?.let { text.append(it) }
            } else if (type == "tool_use") {
                toolCalls += ToolCall(
                    id = block.str("id") ?: "call_$idx",
                    name = block.str("name").orEmpty(),
                    // input 是对象，内部 arguments 是字符串
                    arguments = block["input"]?.toString() ?: "{}",
                )
            }
        }

        val finish = parseFinish(json.str("stop_reason") ?: "end_turn")
        val usage = (json["usage"] as? JsonObject)?.let {
            val prompt = it.int("input_tokens") ?: 0
            val completion = it.int("output_tokens") ?: 0
            ModelEvent.Usage(prompt, completion, prompt + completion)
        }

        return ModelResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                parts = if (text.isNotEmpty()) listOf(ContentPart.Text(text.toString())) else emptyList(),
                toolCalls = toolCalls,
                provider = profile.providerType.name,
                modelId = profile.modelId,
            ),
            usage = usage,
            finishReason = finish,
        )
    }

    /** 把统一事件序列收集成一个结果（非流式复用流式解析时用）。 */
    private fun resultOf(events: List<ModelEvent>): ModelResult {
        val accumulator = ToolCallAccumulator()
        val text = StringBuilder()
        var finish = FinishReason.STOP
        var usage: ModelEvent.Usage? = null
        var failure: Throwable? = null

        events.forEach { event ->
            when (event) {
                is ModelEvent.TextDelta -> text.append(event.text)
                is ModelEvent.ReasoningDelta -> Unit
                is ModelEvent.ToolCallDelta -> accumulator.onDelta(event)
                is ModelEvent.Usage -> usage = event
                is ModelEvent.Completed -> finish = event.finishReason
                is ModelEvent.Failed -> failure = event.error
            }
        }

        failure?.let { throw it }

        return ModelResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                parts = if (text.isNotEmpty()) listOf(ContentPart.Text(text.toString())) else emptyList(),
                toolCalls = accumulator.build(),
                provider = profile.providerType.name,
                modelId = profile.modelId,
            ),
            usage = usage,
            finishReason = finish,
        )
    }

    private fun parseFinish(raw: String): FinishReason = when (raw.lowercase()) {
        "end_turn", "stop_sequence" -> FinishReason.STOP
        "max_tokens" -> FinishReason.LENGTH
        "tool_use" -> FinishReason.TOOL_CALLS
        "refusal" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** 安全取字符串字段（字段缺失或类型不符时返回 null）。 */
private fun JsonObject.str(key: String): String? = this[key].asString()

/** 安全取整型字段。 */
private fun JsonObject.int(key: String): Int? = this[key].asInt()

/** JSON 元素转字符串；不是字符串/数字时返回 null。 */
private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

/** JSON 元素转整数；不是数字时返回 null。 */
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
