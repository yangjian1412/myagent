package com.mynok.myagent.core.model

import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 从服务端拉回来的一个模型条目。
 *
 * [contextWindow] / [maxOutputTokens] 只有部分服务端会返回：
 * - Gemini `/v1beta/models` 有 `inputTokenLimit` / `outputTokenLimit`
 * - OpenRouter `/api/v1/models` 有 `context_length` / `top_provider.max_completion_tokens`
 * - OpenAI、Anthropic 原生接口**不返回**，此时为 null
 */
data class RemoteModel(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    /** 服务端明确给出的思考等级；null 表示没这个信息。 */
    val reasoningEfforts: List<ReasoningEffort>? = null,
)

/** 拉取模型列表的结果。 */
sealed interface CatalogResult {
    /** [endpoint] 是实际请求的地址，方便排错。 */
    data class Success(val models: List<RemoteModel>, val endpoint: String) : CatalogResult
    data class Failure(val message: String) : CatalogResult
}

/**
 * 模型列表探测。
 *
 * 三种原生协议各自有自己的 `/models`，字段也不一样，这里统一成 [RemoteModel]。
 * 拉不到上限信息时上层会退回 [ModelPresets] 的预设值。
 */
class ModelCatalog(private val http: Http) {

    suspend fun list(
        providerType: ProviderType,
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 20,
    ): CatalogResult {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return CatalogResult.Failure("Base URL 不能为空")
        val key = apiKey?.takeIf { it.isNotBlank() }
        return when (providerType) {
            ProviderType.OPENAI_COMPAT -> listOpenAiCompatible(base, key, timeoutSeconds)
            ProviderType.ANTHROPIC -> listAnthropic(base, key, timeoutSeconds)
            ProviderType.GEMINI -> listGemini(base, key, timeoutSeconds)
        }
    }

    private suspend fun listOpenAiCompatible(
        base: String,
        key: String?,
        timeoutSeconds: Long,
    ): CatalogResult {
        // 用户填的可能是 https://host/v1，也可能只填到域名
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val response = runCatching {
            http.execute(
                HttpRequest(
                    url = url,
                    headers = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        }.getOrElse { return CatalogResult.Failure("连接失败：${it.message ?: it::class.simpleName}") }

        if (!response.isSuccessful) {
            return CatalogResult.Failure("HTTP ${response.code}：${response.body.take(200)}")
        }

        val root = runCatching { MyAgentJson.parseToJsonElement(response.body) }.getOrNull()
            ?: return CatalogResult.Failure("返回的不是 JSON")
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            else -> null
        } ?: return CatalogResult.Failure("返回里没有 data 数组")

        val models = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
            RemoteModel(
                id = id,
                displayName = obj.string("display_name") ?: obj.string("name"),
                contextWindow = obj.intAny(
                    "context_length", "context_window", "max_context_length",
                    "max_model_len", "max_input_tokens", "input_token_limit",
                ),
                maxOutputTokens = obj.intAny(
                    "max_output_tokens", "max_completion_tokens", "max_tokens",
                    "output_token_limit",
                ) ?: (obj["top_provider"] as? JsonObject)?.intAny("max_completion_tokens"),
                supportsVision = obj.visionFlag(),
                supportsTools = obj.toolsFlag(),
                reasoningEfforts = obj.reasoningFlag(),
            )
        }.sortedBy { it.id }

        return if (models.isEmpty()) {
            CatalogResult.Failure("模型列表为空")
        } else {
            CatalogResult.Success(models, url)
        }
    }

    private suspend fun listAnthropic(
        base: String,
        key: String?,
        timeoutSeconds: Long,
    ): CatalogResult {
        val root = if (base.endsWith("/v1")) base.removeSuffix("/v1") else base
        val url = "$root/v1/models?limit=1000"
        val response = runCatching {
            http.execute(
                HttpRequest(
                    url = url,
                    headers = buildMap {
                        key?.let { put("x-api-key", it) }
                        put("anthropic-version", "2023-06-01")
                    },
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        }.getOrElse { return CatalogResult.Failure("连接失败：${it.message ?: it::class.simpleName}") }

        if (!response.isSuccessful) {
            return CatalogResult.Failure("HTTP ${response.code}：${response.body.take(200)}")
        }
        val json = runCatching { MyAgentJson.parseToJsonElement(response.body) }.getOrNull()
            as? JsonObject ?: return CatalogResult.Failure("返回的不是 JSON 对象")
        val array = json["data"] as? JsonArray ?: return CatalogResult.Failure("返回里没有 data 数组")

        val models = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            // Anthropic 不返回上下文/输出上限，交给上层预设表
            RemoteModel(id = id, displayName = obj.string("display_name"))
        }.sortedBy { it.id }

        return if (models.isEmpty()) {
            CatalogResult.Failure("模型列表为空")
        } else {
            CatalogResult.Success(models, url)
        }
    }

    private suspend fun listGemini(
        base: String,
        key: String?,
        timeoutSeconds: Long,
    ): CatalogResult {
        val root = base
            .removeSuffix("/v1beta")
            .removeSuffix("/v1alpha")
            .removeSuffix("/v1")
        val url = "$root/v1beta/models?pageSize=1000"
        val response = runCatching {
            http.execute(
                HttpRequest(
                    url = url,
                    headers = key?.let { mapOf("x-goog-api-key" to it) }.orEmpty(),
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        }.getOrElse { return CatalogResult.Failure("连接失败：${it.message ?: it::class.simpleName}") }

        if (!response.isSuccessful) {
            return CatalogResult.Failure("HTTP ${response.code}：${response.body.take(200)}")
        }
        val json = runCatching { MyAgentJson.parseToJsonElement(response.body) }.getOrNull()
            as? JsonObject ?: return CatalogResult.Failure("返回的不是 JSON 对象")
        val array = json["models"] as? JsonArray ?: return CatalogResult.Failure("返回里没有 models 数组")

        val models = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val rawName = obj.string("name") ?: return@mapNotNull null
            // 只保留能对话的模型
            val methods = obj["supportedGenerationMethods"] as? JsonArray
            if (methods != null && methods.none { (it as? JsonPrimitive)?.content == "generateContent" }) {
                return@mapNotNull null
            }
            RemoteModel(
                id = rawName.removePrefix("models/"),
                displayName = obj.string("displayName"),
                contextWindow = obj.intAny("inputTokenLimit"),
                maxOutputTokens = obj.intAny("outputTokenLimit"),
                supportsTools = true,
            )
        }.sortedBy { it.id }

        return if (models.isEmpty()) {
            CatalogResult.Failure("模型列表为空")
        } else {
            CatalogResult.Success(models, url)
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intAny(vararg keys: String): Int? {
    keys.forEach { key ->
        val value = this[key] as? JsonPrimitive ?: return@forEach
        value.intOrNull?.let { return it }
        // 有些网关把数字写成字符串
        value.content.toIntOrNull()?.let { return it }
    }
    return null
}

/** OpenRouter 用 `architecture.input_modalities` 标注多模态。 */
private fun JsonObject.visionFlag(): Boolean? {
    val architecture = this["architecture"] as? JsonObject
    val inputs = architecture?.get("input_modalities") as? JsonArray ?: return null
    return inputs.any { (it as? JsonPrimitive)?.content == "image" }
}

/** OpenRouter 用 `supported_parameters` 标注能力。 */
private fun JsonObject.toolsFlag(): Boolean? {
    val params = supportedParameters() ?: return null
    return params.any { it == "tools" || it == "tool_choice" }
}

/**
 * 只有 OpenRouter 这类聚合平台会给出思考能力：
 * `supported_parameters` 含 `reasoning` / `reasoning_effort` 才算支持。
 * 返回 null 表示服务端没给信息，上层沿用预设。
 */
private fun JsonObject.reasoningFlag(): List<ReasoningEffort>? {
    val params = supportedParameters() ?: return null
    if (params.none { it == "reasoning" || it == "reasoning_effort" || it == "include_reasoning" }) {
        return null
    }
    return if (params.contains("reasoning_effort")) {
        listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    } else {
        listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    }
}

private fun JsonObject.supportedParameters(): List<String>? {
    val array = this["supported_parameters"] as? JsonArray ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.content }
}
