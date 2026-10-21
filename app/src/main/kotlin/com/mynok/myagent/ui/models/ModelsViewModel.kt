package com.mynok.myagent.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.core.model.CatalogResult
import com.mynok.myagent.core.model.ModelCatalog
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.core.model.RemoteModel
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.data.db.ModelProfileEntity
import com.mynok.myagent.data.repo.Mappers
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 连接自检的结果。 */
sealed interface ProbeResult {
    data object Idle : ProbeResult
    data object Probing : ProbeResult
    data class Success(val detail: String) : ProbeResult
    data class Failure(val detail: String) : ProbeResult
}

/** 拉取模型列表的状态。 */
sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Loaded(val models: List<RemoteModel>, val endpoint: String) : CatalogState
    data class Error(val message: String) : CatalogState
}

data class ModelsUiState(
    val profiles: List<ModelProfileEntity> = emptyList(),
    val probe: ProbeResult = ProbeResult.Idle,
    val probeFor: String? = null,
    val message: String? = null,
    val catalog: CatalogState = CatalogState.Idle,
)

class ModelsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.repository

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeProfiles().collect { list ->
                _state.update { it.copy(profiles = list) }
            }
        }
        // 注意：collect 永不返回，所以种子数据要放在单独的协程里
        viewModelScope.launch { seedBuiltinsIfEmpty() }
    }

    private suspend fun seedBuiltinsIfEmpty() {
        if (repo.profileCount() > 0) return
        repo.upsertProfile(
            ModelProfileEntity(
                id = "builtin-strata",
                name = "本地模型 (strata)",
                providerType = ProviderType.OPENAI_COMPAT.name,
                baseUrl = "http://192.168.1.100:8080/v1",
                modelId = "local-model",
                contextWindow = 32_768,
                maxOutputTokens = 4_096,
                builtin = true,
                sortOrder = 0,
            ),
        )
    }

    fun save(
        id: String?,
        name: String,
        providerType: ProviderType,
        baseUrl: String,
        modelId: String,
        apiKey: String?,
        contextWindow: Int,
        maxOutputTokens: Int,
        supportsTools: Boolean,
        supportsVision: Boolean,
        supportsFileInput: Boolean,
        temperature: Double?,
        reasoningLevels: List<ReasoningEffort> = emptyList(),
    ) {
        viewModelScope.launch {
            // 保存链路里有网络/加密/数据库，任何一步都可能抛，不能让它把进程带崩
            runCatching {
                val profileId = id ?: UUID.randomUUID().toString()
                val secretId = if (id != null) {
                    repo.getProfile(id)?.secretId
                } else {
                    null
                }
                val newSecretId = if (!apiKey.isNullOrBlank()) {
                    val sid = secretId ?: "sec-$profileId"
                    container.secretStore.put(sid, apiKey)
                    sid
                } else {
                    secretId
                }
                val existing = repo.getProfile(profileId)
                repo.upsertProfile(
                    ModelProfileEntity(
                        id = profileId,
                        name = name.ifBlank { modelId },
                        providerType = providerType.name,
                        baseUrl = baseUrl.trimEnd('/'),
                        modelId = modelId,
                        secretId = newSecretId,
                        headersJson = existing?.headersJson ?: "{}",
                        supportsTools = supportsTools,
                        supportsVision = supportsVision,
                        supportsFileInput = supportsFileInput,
                        supportsStreaming = true,
                        contextWindow = contextWindow,
                        maxOutputTokens = maxOutputTokens,
                        temperature = temperature,
                        topP = existing?.topP,
                        reasoningLevels = ReasoningEffort.join(reasoningLevels),
                        extraBodyJson = existing?.extraBodyJson ?: "{}",
                        builtin = existing?.builtin ?: false,
                        sortOrder = existing?.sortOrder ?: (_state.value.profiles.size),
                    ),
                )
                _state.update { it.copy(message = "已保存「${name.ifBlank { modelId }}」") }
            }.onFailure { e ->
                _state.update {
                    it.copy(message = "保存失败：${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repo.deleteProfile(id) }.onFailure { e ->
                _state.update { it.copy(message = "删除失败：${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * 拉取模型列表。
     *
     * apiKey 为空时用已有档案里存的 key（编辑态），这样用户不用每次重填。
     */
    fun fetchModels(
        providerType: ProviderType,
        baseUrl: String,
        apiKey: String?,
        secretId: String?,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(catalog = CatalogState.Loading) }
            val key = apiKey?.takeIf { it.isNotBlank() }
                ?: secretId?.let { runCatching { container.secretStore.get(it) }.getOrNull() }
            val result = ModelCatalog(container.http)
                .list(providerType, baseUrl, key)
            _state.update { s ->
                s.copy(
                    catalog = when (result) {
                        is CatalogResult.Success -> CatalogState.Loaded(result.models, result.endpoint)
                        is CatalogResult.Failure -> CatalogState.Error(result.message)
                    },
                )
            }
        }
    }

    fun resetCatalog() = _state.update { it.copy(catalog = CatalogState.Idle) }

    /**
     * 连接自检：按顺序试探 OpenAI 兼容 / Anthropic / Gemini 三种协议，
     * 报告识别到的协议与可用性。
     */
    fun probe(entity: ModelProfileEntity) {
        viewModelScope.launch {
            _state.update { it.copy(probe = ProbeResult.Probing, probeFor = entity.id) }
            val apiKey = entity.secretId?.let { container.secretStore.get(it) }
            val result = runCatching { detectProtocol(entity, apiKey) }
                .getOrElse { ProbeResult.Failure("自检失败：${it.message}") }
            _state.update { it.copy(probe = result, probeFor = entity.id) }
        }
    }

    private suspend fun detectProtocol(
        entity: ModelProfileEntity,
        apiKey: String?,
    ): ProbeResult {
        val base = entity.baseUrl.trimEnd('/')
        val http = container.http
        val report = StringBuilder()

        // 1) OpenAI 兼容：GET {base}/models
        val modelsUrl = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val modelsResp = runCatching {
            http.execute(
                HttpRequest(
                    url = modelsUrl,
                    headers = buildMap {
                        apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
                    },
                    timeoutSeconds = 15,
                ),
            )
        }.getOrNull()

        if (modelsResp != null && modelsResp.isSuccessful) {
            val ids = runCatching {
                val arr = com.mynok.myagent.core.util.MyAgentJson
                    .parseToJsonElement(modelsResp.body)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("data")
                    .let { it as? kotlinx.serialization.json.JsonArray }
                arr?.mapNotNull { item ->
                    (item as? kotlinx.serialization.json.JsonObject)
                        ?.get("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                }
            }.getOrNull().orEmpty()
            report.appendLine("✓ OpenAI 兼容：$modelsUrl 可用")
            if (ids.isNotEmpty()) {
                report.appendLine("  可用模型：${ids.take(8).joinToString(", ")}")
            }
            report.appendLine("  当前配置的模型 id：${entity.modelId}")
            return ProbeResult.Success(report.toString().trim())
        }

        // 2) Anthropic
        val anthropicBase = if (base.endsWith("/v1")) base.removeSuffix("/v1") else base
        val anthropicResp = runCatching {
            http.execute(
                HttpRequest(
                    url = "$anthropicBase/v1/models",
                    headers = buildMap {
                        apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
                        put("anthropic-version", "2023-06-01")
                    },
                    timeoutSeconds = 15,
                ),
            )
        }.getOrNull()
        if (anthropicResp != null && anthropicResp.isSuccessful) {
            report.appendLine("✓ Anthropic 原生：$anthropicBase/v1/models 可用")
            return ProbeResult.Success(report.toString().trim())
        }

        // 3) Gemini
        val geminiBase = if (base.endsWith("/v1beta")) base.removeSuffix("/v1beta") else base
        val geminiResp = runCatching {
            http.execute(
                HttpRequest(
                    url = "$geminiBase/v1beta/models",
                    headers = buildMap {
                        apiKey?.takeIf { it.isNotBlank() }?.let { put("x-goog-api-key", it) }
                    },
                    timeoutSeconds = 15,
                ),
            )
        }.getOrNull()
        if (geminiResp != null && geminiResp.isSuccessful) {
            report.appendLine("✓ Gemini 原生：$geminiBase/v1beta/models 可用")
            return ProbeResult.Success(report.toString().trim())
        }

        // 全部失败：把三个结果都报出来
        report.appendLine("✗ 未能识别协议。三种探测结果：")
        report.appendLine("  OpenAI 兼容 ${modelsUrl} → ${modelsResp?.code ?: "连接失败"}")
        report.appendLine("  Anthropic  $anthropicBase/v1/models → ${anthropicResp?.code ?: "连接失败"}")
        report.appendLine("  Gemini     $geminiBase/v1beta/models → ${geminiResp?.code ?: "连接失败"}")
        return ProbeResult.Failure(report.toString().trim())
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ModelsViewModel(container) as T
        }
    }
}
