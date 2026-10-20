package com.mynok.myagent.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.ToolSecretKeys
import com.mynok.myagent.data.db.McpServerEntity
import com.mynok.myagent.data.db.SkillEntity
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 工具条目及其策略状态。 */
data class ToolEntry(
    val name: String,
    val description: String,
    val risk: ToolRisk = ToolRisk.READ_ONLY,
    val isMcp: Boolean = false,
    val serverName: String? = null,
    val enabled: Boolean = true,
    val requireApproval: Boolean = false,
    /** 破坏性工具的「每次都问」不可关闭。 */
    val approvalMandatory: Boolean = false,
) {
    val riskLabel: String
        get() = when (risk) {
            ToolRisk.READ_ONLY -> "只读"
            ToolRisk.WRITE -> "写入"
            ToolRisk.DESTRUCTIVE -> "破坏性"
        }
}

data class ToolsUiState(
    val builtIn: List<ToolEntry> = emptyList(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val skills: List<SkillEntity> = emptyList(),
    /** 已配置的集成密钥（只存键名，不把密钥读回界面）。 */
    val configuredSecrets: Set<String> = emptySet(),
    val message: String? = null,
    val busy: Boolean = false,
)

class ToolsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.repository
    private val policies = container.toolPolicyStore

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            policies.load()
            repo.observeMcpServers().collect { servers ->
                _state.update { it.copy(mcpServers = servers) }
            }
        }
        viewModelScope.launch {
            repo.observeSkills().collect { skills ->
                _state.update { it.copy(skills = skills) }
            }
        }
        loadSecrets()
    }

    // ------------------------------------------------------------ 集成密钥

    /** 只探测「配没配」，绝不把明文读回内存/界面。 */
    private fun loadSecrets() {
        viewModelScope.launch {
            val configured = ToolSecretKeys.all.filter { name ->
                runCatching { container.toolSecrets.get(name) }.getOrNull() != null
            }.toSet()
            _state.update { it.copy(configuredSecrets = configured) }
        }
    }

    /** 保存一个集成密钥；传空字符串表示清除。 */
    fun saveSecret(name: String, value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            runCatching {
                if (trimmed.isEmpty()) {
                    container.secretStore.remove("tool:$name")
                } else {
                    container.secretStore.put("tool:$name", trimmed)
                }
            }.onFailure { e ->
                _state.update { it.copy(message = "保存失败：${e.message ?: "未知错误"}") }
                return@launch
            }
            loadSecrets()
            val label = ToolSecretKeys.label(name)
            _state.update {
                it.copy(message = if (trimmed.isEmpty()) "已清除 $label" else "已保存 $label")
            }
        }
    }

    // ------------------------------------------------------------ 技能

    fun saveSkill(id: String?, name: String, description: String, prompt: String, forceTools: String) {
        viewModelScope.launch {
            val skillId = id ?: "skill-${UUID.randomUUID()}"
            repo.upsertSkill(
                SkillEntity(
                    id = skillId,
                    name = name.ifBlank { "未命名技能" },
                    description = description,
                    prompt = prompt,
                    forceTools = forceTools.trim(),
                    sortOrder = _state.value.skills.size,
                ),
            )
            _state.update { it.copy(message = "已保存技能「${name.ifBlank { "未命名技能" }}」") }
        }
    }

    fun deleteSkill(id: String) {
        viewModelScope.launch {
            repo.deleteSkill(id)
            _state.update { it.copy(message = "已删除技能") }
        }
    }

    /** 同步一次工具清单与策略状态。 */
    fun refreshBuiltIn(tools: List<Tool>) {
        viewModelScope.launch {
            policies.syncTools(tools)
            _state.update { it.copy(builtIn = buildEntries(tools)) }
        }
    }

    private fun buildEntries(tools: List<Tool>): List<ToolEntry> {
        val policyMap = policies.policies.value
        return tools.map { tool ->
            val p = policyMap[tool.spec.name]
            ToolEntry(
                name = tool.spec.name,
                description = tool.spec.description,
                risk = tool.risk,
                isMcp = tool.spec.name.contains("__"),
                enabled = p?.enabled ?: true,
                requireApproval = policies.requiresApproval(tool),
                approvalMandatory = policies.isApprovalMandatory(tool),
            )
        }
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            policies.setEnabled(toolName, enabled)
            refreshBuiltIn(container.toolRegistry.all())
        }
    }

    fun setToolRequireApproval(toolName: String, require: Boolean, risk: ToolRisk) {
        viewModelScope.launch {
            policies.setRequireApproval(toolName, require, risk)
            refreshBuiltIn(container.toolRegistry.all())
        }
    }

    fun addMcpServer(name: String, url: String, token: String?) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val secretId = if (!token.isNullOrBlank()) {
                val sid = "mcp-$id"
                container.secretStore.put(sid, token)
                sid
            } else {
                null
            }
            repo.upsertMcpServer(
                McpServerEntity(
                    id = id,
                    name = name.ifBlank { url },
                    url = url,
                    secretId = secretId,
                ),
            )
            _state.update { it.copy(message = "已添加 MCP 服务「${name.ifBlank { url }}」") }
        }
    }

    fun removeMcpServer(id: String) {
        viewModelScope.launch { repo.deleteMcpServer(id) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun setBusy(v: Boolean) = _state.update { it.copy(busy = v) }

    /** 连接所有已启用的 MCP 服务，并把它们的工具注册进注册表。 */
    fun syncMcp() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = runCatching { container.mcpManager.sync() }
                .getOrElse { error ->
                    _state.update { it.copy(busy = false, message = "同步失败：${error.message}") }
                    return@launch
                }
            val message = buildString {
                append("已连接 ${result.connected.size} 个 MCP 服务，注册 ${result.toolCount} 个工具")
                if (result.failed.isNotEmpty()) {
                    append("；失败：")
                    append(result.failed.joinToString("；") { "${it.first} — ${it.second}" })
                }
            }
            _state.update { it.copy(busy = false, message = message) }
            refreshBuiltIn(container.toolRegistry.all())
        }
    }

    /** 保存用户新授权的目录。 */
    fun grantDirectories(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            container.workspaceStore.save(uris)
            container.workspaceStore.load(container.workspace)
            _state.update { it.copy(message = "已授权 ${uris.size} 个目录") }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ToolsViewModel(container) as T
        }
    }
}
