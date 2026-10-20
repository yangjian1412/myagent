package com.mynok.myagent.core.model.openai

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容协议适配器。
 *
 * 一个适配器覆盖：OpenAI、DeepSeek、Kimi/月之暗面、Qwen（DashScope 兼容模式）、
 * OpenRouter、SiliconFlow、Groq、Ollama、llama.cpp server、vLLM，以及用户自建的
 * 局域网服务（strata）等——它们在 HTTP 层的差异只有 baseUrl 和鉴权头。
 */
class OpenAICompatProvider(
    override val profile: ModelProfile,
    private val http: Http,
    private val apiKey: String? = null,
) : ModelProvider {

    private val endpoint: String
        get() {
            val base = profile.baseUrl.trimEnd('/')
            return when {
                base.endsWith("/chat/completions") -> base
                base.endsWith("/v1") -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
        }

    override suspend fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        try {
            val body = buildRequestBody(request)
            var finish = FinishReason.STOP
            var usage: ModelEvent.Usage? = null

            // 每收到一条 SSE 就解析并 emit，界面才能逐字出字
            suspend fun handle(event: SseEvent) {
                val data = event.data.trim()
                if (data.isEmpty() || data == "[DONE]") return
                val json = runCatching { MyAgentJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

                json["error"]?.let { err ->
                    val msg = runCatching {
                        err.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: (err as? JsonPrimitive)?.contentOrNull ?: "未知错误"
                    throw ModelException("模型服务返回错误：$msg", rawBody = data)
                }

                json["usage"]?.let { u -> usage = parseUsage(u) }

                val choices = json["choices"]?.jsonArray ?: return
                for (choice in choices) {
                    val choiceObj = choice.jsonObject
                    choiceObj["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                        finish = parseFinish(raw)
                    }
                    val delta = choiceObj["delta"] ?: choiceObj["message"] ?: continue
                    val deltaObj = delta.jsonObject

                    deltaObj["content"]?.let { content ->
                        val text = when (content) {
                            is JsonPrimitive -> content.contentOrNull.orEmpty()
                            is JsonArray -> content.jsonArray
                                .filterIsInstance<JsonObject>()
                                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                                .joinToString("")

                            else -> ""
                        }
                        if (text.isNotEmpty()) emit(ModelEvent.TextDelta(text))
                    }

                    deltaObj["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) emit(ModelEvent.ReasoningDelta(it))
                    }
                    deltaObj["reasoning"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) emit(ModelEvent.ReasoningDelta(it))
                    }

                    deltaObj["tool_calls"]?.jsonArray?.forEachIndexed { fallbackIndex, element ->
                        val callObj = element.jsonObject
                        val index = callObj["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                        val id = callObj["id"]?.jsonPrimitive?.contentOrNull
                        val fn = callObj["function"]?.jsonObject
                        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                        val args = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                        emit(ModelEvent.ToolCallDelta(index = index, id = id, name = name, argumentsDelta = args))
                    }
                }
            }

            val response = http.streamSseEvents(
                HttpRequest(
                    url = endpoint,
                    method = "POST",
                    headers = buildHeaders(),
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
                headers = buildHeaders(),
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

    private fun buildHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "text/event-stream",
        )
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        headers += profile.headers
        return headers
    }

    internal fun buildRequestBody(request: ModelRequest): JsonObject = buildJsonObject {
        put("model", profile.modelId)
        put("stream", request.stream)

        val messages = buildJsonArray {
            request.system?.takeIf { it.isNotBlank() }?.let {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    },
                )
            }
            request.messages.forEach { add(encodeMessage(it)) }
        }
        put("messages", messages)

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("tool_choice", "auto")
        }

        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        request.maxTokens?.let { put("max_tokens", it) }

        // 只有用户显式选了思考强度才带上，避免本地服务因为不认识的字段直接报错
        request.reasoningEffort.takeIf { it.isOn }?.let { put("reasoning_effort", it.wire) }

        for ((k, v) in request.extra) {
            put(k, v)
        }
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.wireName())
        when (message.role) {
            Role.TOOL -> {
                message.toolCallId?.let { put("tool_call_id", it) }
                put("content", message.text)
            }

            Role.ASSISTANT -> {
                if (message.toolCalls.isEmpty()) {
                    put("content", encodeContent(message.parts))
                } else {
                    put("content", message.text.ifBlank { null as String? })
                    put(
                        "tool_calls",
                        buildJsonArray {
                            message.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

            else -> put("content", encodeContent(message.parts))
        }
    }

    /** 内容可以是纯字符串，也可以是分片数组（带图片时必须用数组）。 */
    private fun encodeContent(parts: List<ContentPart>): JsonElement {
        if (parts.isEmpty()) return JsonPrimitive("")
        if (parts.size == 1 && parts[0] is ContentPart.Text) {
            return JsonPrimitive((parts[0] as ContentPart.Text).text)
        }
        return buildJsonArray {
            parts.forEach { part ->
                when (part) {
                    is ContentPart.Text -> add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        },
                    )

                    is ContentPart.Image -> add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject {
                                    put("url", "data:${part.mimeType};base64,${part.base64}")
                                },
                            )
                        },
                    )

                    is ContentPart.File -> add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "[附件 ${part.name} (${part.mimeType})]")
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    private fun parseNonStreaming(json: JsonObject): ModelResult {
        json["error"]?.let { err ->
            val msg = runCatching {
                err.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "未知错误"
            throw ModelException("模型服务返回错误：$msg", rawBody = json.toString())
        }

        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ModelException("模型服务返回的响应缺少 choices", rawBody = json.toString())

        val messageObj = choice["message"]?.jsonObject
            ?: throw ModelException("模型服务返回的响应缺少 message", rawBody = json.toString())

        val text = messageObj["content"]?.let { content ->
            when (content) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray -> content.jsonArray
                    .filterIsInstance<JsonObject>()
                    .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")

                else -> ""
            }
        }.orEmpty()

        val toolCalls = messageObj["tool_calls"]?.jsonArray?.mapIndexedNotNull { idx, element ->
            val obj = element.jsonObject
            val fn = obj["function"]?.jsonObject ?: return@mapIndexedNotNull null
            com.mynok.myagent.core.model.ToolCall(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "call_$idx",
                name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }.orEmpty()

        val finish = parseFinish(choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop")
        val usage = json["usage"]?.let { parseUsage(it) }

        return ModelResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                parts = if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList(),
                toolCalls = toolCalls,
                provider = profile.providerType.name,
                modelId = profile.modelId,
            ),
            usage = usage,
            finishReason = finish,
        )
    }

    private fun parseUsage(element: JsonElement): ModelEvent.Usage {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return ModelEvent.Usage()
        val prompt = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completion = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val total = obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: (prompt + completion)
        return ModelEvent.Usage(prompt, completion, total)
    }

    private fun parseFinish(raw: String): FinishReason = when (raw.lowercase()) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }
}

/** 角色 → 线上协议里的名字。 */
internal fun Role.wireName(): String = when (this) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> "tool"
}
