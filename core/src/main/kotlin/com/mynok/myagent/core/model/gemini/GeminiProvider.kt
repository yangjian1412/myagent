package com.mynok.myagent.core.model.gemini

import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelException
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelProvider
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ModelResult
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.SseEvent
import com.mynok.myagent.core.model.ToolCall
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
 * Google Gemini 原生协议适配器（generateContent / streamGenerateContent）。
 *
 * 与 OpenAI 兼容协议的几处关键差异：
 * - 模型名在 URL 里（`/v1beta/models/{model}:generateContent`），不在 body 里；
 * - 鉴权用 `x-goog-api-key` 请求头，不用 query 参数；
 * - 系统提示走顶层 `systemInstruction`，`contents` 里不允许出现 system 角色；
 * - 工具声明是 `tools[].functionDeclarations[]`（一个 tools 元素放多个声明）；
 * - 工具结果用 `functionResponse`，承载它的 content 角色是 `user`；
 * - 思考过程是 `part.thought == true` 的文本块。
 */
class GeminiProvider(
    override val profile: ModelProfile,
    private val http: Http,
    private val apiKey: String? = null,
) : ModelProvider {

    /** API 根路径：baseUrl 已经带 /v1beta 时不重复拼。 */
    private val apiRoot: String
        get() {
            val base = profile.baseUrl.trimEnd('/')
            return if (base.endsWith("/v1beta")) base else "$base/v1beta"
        }

    private fun endpoint(stream: Boolean): String {
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$apiRoot/models/${profile.modelId}:$action"
    }

    // ------------------------------------------------------------------ 流式

    override suspend fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        try {
            val body = buildRequestBody(request)
            val headers = buildHeaders(stream = true)

            var toolIndex = 0
            var finish = FinishReason.STOP
            var usage: ModelEvent.Usage? = null

            suspend fun handle(event: SseEvent) {
                val data = event.data.trim()
                if (data.isEmpty() || data == "[DONE]") return
                val json = runCatching { MyAgentJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

                json["error"]?.let { err ->
                    throw ModelException("模型服务返回错误：${errorMessage(err)}", rawBody = data)
                }

                val chunk = parseChunk(json)
                chunk.texts.forEach { text ->
                    if (text.isNotEmpty()) emit(ModelEvent.TextDelta(text))
                }
                chunk.reasonings.forEach { text ->
                    if (text.isNotEmpty()) emit(ModelEvent.ReasoningDelta(text))
                }
                chunk.functionCalls.forEach { call ->
                    val index = toolIndex++
                    emit(
                        ModelEvent.ToolCallDelta(
                            index = index,
                            id = "call_${call.name}_$index",
                            name = call.name,
                            argumentsDelta = call.args.toString(),
                        ),
                    )
                }
                chunk.usage?.let { usage = it }
                chunk.finishReason?.let { finish = parseFinish(it) }
            }

            val response = http.streamSseEvents(
                HttpRequest(
                    url = endpoint(stream = true),
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

            usage?.let { emit(it) }
            emit(ModelEvent.Completed(finish))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            emit(ModelEvent.Failed(t))
        }
    }

    // ------------------------------------------------------------------ 非流式

    override suspend fun complete(request: ModelRequest): ModelResult {
        val body = buildRequestBody(request.copy(stream = false))
        val response = http.execute(
            HttpRequest(
                url = endpoint(stream = false),
                method = "POST",
                headers = buildHeaders(stream = false),
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
        val json = runCatching { MyAgentJson.parseToJsonElement(response.body).jsonObject }
            .getOrElse { throw ModelException("模型服务返回的不是合法 JSON", rawBody = response.body) }
        return parseNonStreaming(json)
    }

    // ------------------------------------------------------------------ 请求

    private fun buildHeaders(stream: Boolean): Map<String, String> {
        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json; charset=utf-8",
        )
        if (stream) headers["Accept"] = "text/event-stream"
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-goog-api-key"] = it }
        headers += profile.headers
        return headers
    }

    internal fun buildRequestBody(request: ModelRequest): JsonObject = buildJsonObject {
        val systemText = collectSystemText(request)
        if (systemText.isNotBlank()) {
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", systemText) })
                        },
                    )
                },
            )
        }

        put(
            "contents",
            buildJsonArray {
                request.messages.forEach { message ->
                    encodeContent(message)?.let { add(it) }
                }
            },
        )

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "functionDeclarations",
                                buildJsonArray {
                                    request.tools.forEach { tool ->
                                        add(
                                            buildJsonObject {
                                                put("name", tool.name)
                                                put("description", tool.description)
                                                put("parameters", tool.parameters)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

        val generationConfig = buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("topP", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
            request.reasoningEffort.takeIf { it.isOn }?.let { effort ->
                put(
                    "thinkingConfig",
                    buildJsonObject { put("thinkingBudget", effort.budgetTokens) },
                )
            }
        }
        if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)

        for ((k, v) in profile.extraBody) put(k, v)
        for ((k, v) in request.extra) put(k, v)
    }

    /**
     * 系统提示文本。
     *
     * Gemini 的 `contents` 里不能出现 system 角色，所以顶层 [ModelRequest.system]
     * 与历史里 SYSTEM 角色的消息都合并进 `systemInstruction`。
     */
    private fun collectSystemText(request: ModelRequest): String {
        val blocks = buildList {
            request.system?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            request.messages.filter { it.role == Role.SYSTEM }.forEach { message ->
                message.text.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return blocks.joinToString("\n\n")
    }

    /** 一条消息 → 一个 content；SYSTEM 角色在 Gemini 里没有对应物，直接丢弃。 */
    private fun encodeContent(message: ChatMessage): JsonObject? {
        if (message.role == Role.SYSTEM) return null
        val role = message.role.geminiRole()

        val parts = buildJsonArray {
            when (message.role) {
                Role.ASSISTANT -> {
                    message.parts.filterIsInstance<ContentPart.Text>().forEach { add(textPart(it.text)) }
                    message.toolCalls.forEach { add(functionCallPart(it)) }
                }

                Role.TOOL -> add(functionResponsePart(message))

                else -> message.parts.forEach { add(encodePart(it)) }
            }
        }
        if (parts.isEmpty()) return null

        return buildJsonObject {
            put("role", role)
            put("parts", parts)
        }
    }

    private fun encodePart(part: ContentPart): JsonElement = when (part) {
        is ContentPart.Text -> textPart(part.text)
        is ContentPart.Image -> inlineDataPart(part.mimeType, part.base64)
        is ContentPart.File -> inlineDataPart(
            part.mimeType.ifBlank { "application/octet-stream" },
            part.base64,
        )
    }

    private fun textPart(text: String): JsonElement = buildJsonObject {
        put("text", text)
    }

    private fun inlineDataPart(mimeType: String, base64: String): JsonElement = buildJsonObject {
        put(
            "inlineData",
            buildJsonObject {
                put("mimeType", mimeType)
                put("data", base64)
            },
        )
    }

    /** 助手发起的工具调用 → `{"functionCall":{"name":...,"args":{...}}}`。 */
    private fun functionCallPart(call: ToolCall): JsonElement = buildJsonObject {
        put(
            "functionCall",
            buildJsonObject {
                put("name", call.name)
                put("args", parseArgs(call.arguments))
            },
        )
    }

    /** 工具结果 → `{"functionResponse":{"name":...,"response":{...}}}`（承载它的角色是 user）。 */
    private fun functionResponsePart(message: ChatMessage): JsonElement = buildJsonObject {
        put(
            "functionResponse",
            buildJsonObject {
                put("name", toolNameOf(message))
                put(
                    "response",
                    buildJsonObject {
                        put("result", message.text)
                    },
                )
            },
        )
    }

    /** 工具参数是 JSON 字符串，这里还原成对象。 */
    private fun parseArgs(raw: String): JsonObject =
        runCatching { MyAgentJson.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    /** 工具结果里带的函数名：优先用消息上的 name，其次从 toolCallId（call_<name>_<n>）反推。 */
    private fun toolNameOf(message: ChatMessage): String {
        message.name?.takeIf { it.isNotBlank() }?.let { return it }
        val id = message.toolCallId?.takeIf { it.isNotBlank() } ?: return "tool"
        if (!id.startsWith("call_")) return "tool"
        val rest = id.removePrefix("call_")
        val cut = rest.lastIndexOf('_')
        if (cut <= 0) return rest
        val suffix = rest.substring(cut + 1)
        return if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) rest.substring(0, cut) else rest
    }

    // ------------------------------------------------------------------ 解析

    private data class FunctionCall(val name: String, val args: JsonObject)

    private data class Chunk(
        val texts: List<String> = emptyList(),
        val reasonings: List<String> = emptyList(),
        val functionCalls: List<FunctionCall> = emptyList(),
        val finishReason: String? = null,
        val usage: ModelEvent.Usage? = null,
    )

    /** 一个 GenerateContentResponse（流式里每条 data 都是一个完整响应）。 */
    private fun parseChunk(json: JsonObject): Chunk {
        val texts = mutableListOf<String>()
        val reasonings = mutableListOf<String>()
        val calls = mutableListOf<FunctionCall>()
        var finish: String? = null

        (json["candidates"] as? JsonArray)?.forEach { element ->
            val candidate = element as? JsonObject ?: return@forEach
            (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { finish = it }

            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return@forEach
            parts.forEach { partElement ->
                val part = partElement as? JsonObject ?: return@forEach
                val text = (part["text"] as? JsonPrimitive)?.contentOrNull
                if (text != null) {
                    val thought = (part["thought"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true
                    if (thought) reasonings += text else texts += text
                }
                val fc = part["functionCall"] as? JsonObject
                if (fc != null) {
                    val name = (fc["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val args = (fc["args"] as? JsonObject) ?: JsonObject(emptyMap())
                    calls += FunctionCall(name, args)
                }
            }
        }

        // 提示被拦截时没有 candidates，只有 promptFeedback.blockReason
        if (finish == null) {
            val blockReason = (json["promptFeedback"] as? JsonObject)
                ?.get("blockReason") as? JsonPrimitive
            blockReason?.contentOrNull?.takeIf { it.isNotBlank() }?.let { finish = "SAFETY" }
        }

        val usage = json["usageMetadata"]?.let { parseUsage(it) }
        return Chunk(
            texts = texts,
            reasonings = reasonings,
            functionCalls = calls,
            finishReason = finish,
            usage = usage,
        )
    }

    private fun parseNonStreaming(json: JsonObject): ModelResult {
        json["error"]?.let { err ->
            throw ModelException("模型服务返回错误：${errorMessage(err)}", rawBody = json.toString())
        }

        val chunk = parseChunk(json)
        val text = chunk.texts.joinToString("")
        val toolCalls = chunk.functionCalls.mapIndexed { index, call ->
            ToolCall(
                id = "call_${call.name}_$index",
                name = call.name,
                arguments = call.args.toString(),
            )
        }

        return ModelResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                parts = if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList(),
                toolCalls = toolCalls,
                provider = profile.providerType.name,
                modelId = profile.modelId,
            ),
            usage = chunk.usage,
            finishReason = parseFinish(chunk.finishReason),
        )
    }

    private fun parseUsage(element: JsonElement): ModelEvent.Usage {
        val obj = element as? JsonObject ?: return ModelEvent.Usage()
        val prompt = (obj["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
        val completion = (obj["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
        val total = (obj["totalTokenCount"] as? JsonPrimitive)?.intOrNull ?: (prompt + completion)
        return ModelEvent.Usage(prompt, completion, total)
    }

    private fun parseFinish(raw: String?): FinishReason = when (raw?.trim()?.uppercase()) {
        "STOP" -> FinishReason.STOP
        "MAX_TOKENS" -> FinishReason.LENGTH
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.CONTENT_FILTER
        "OTHER" -> FinishReason.UNKNOWN
        null, "" -> FinishReason.STOP
        else -> FinishReason.UNKNOWN
    }

    private fun errorMessage(error: JsonElement): String {
        val obj = error as? JsonObject
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        if (!message.isNullOrBlank()) return message
        val status = (obj?.get("status") as? JsonPrimitive)?.contentOrNull
        return status?.takeIf { it.isNotBlank() } ?: "未知错误"
    }
}

/** 角色 → Gemini 线上协议里的名字。 */
internal fun Role.geminiRole(): String = when (this) {
    Role.SYSTEM -> "user" // Gemini 没有 system 角色，系统提示走 systemInstruction
    Role.USER -> "user"
    Role.ASSISTANT -> "model"
    Role.TOOL -> "user"
}
