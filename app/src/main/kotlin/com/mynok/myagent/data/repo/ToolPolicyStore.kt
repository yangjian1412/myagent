package com.mynok.myagent.data.repo

import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolPolicy
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.data.db.ToolPolicyEntity
import com.mynok.myagent.data.db.MyAgentDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 工具策略存储。
 *
 * 三档风险 + 每工具独立策略：
 * - READ_ONLY：默认**不问**（可手动改成每次都问）
 * - WRITE：默认**要问**（可手动改成不问）
 * - DESTRUCTIVE：**永远要问**，不允许关掉
 *
 * 另外每个工具可以单独禁用，禁用后模型看到的是「工具不可用」。
 */
class ToolPolicyStore(
    private val db: MyAgentDatabase,
) : ToolPolicy {

    private val dao = db.toolPolicyDao()

    private val _policies = MutableStateFlow<Map<String, ToolPolicyEntity>>(emptyMap())
    val policies: StateFlow<Map<String, ToolPolicyEntity>> = _policies.asStateFlow()

    suspend fun load() {
        _policies.value = dao.observeAll().first().associateBy { it.toolName }
    }

    /** 把当前注册表里的工具都写进策略表（保留已有设置）。 */
    suspend fun syncTools(tools: List<Tool>, mcpServerOf: (String) -> String? = { null }) {
        val known = _policies.value
        tools.forEach { tool ->
            val name = tool.spec.name
            if (name in known) return@forEach
            val entity = ToolPolicyEntity(
                toolName = name,
                enabled = true,
                requireApproval = defaultRequiresApproval(tool),
                isMcp = name.contains("__"),
                serverId = mcpServerOf(name),
            )
            dao.upsert(entity)
        }
        _policies.value = dao.observeAll().first().associateBy { it.toolName }
    }

    suspend fun setEnabled(toolName: String, enabled: Boolean) {
        val current = _policies.value[toolName] ?: ToolPolicyEntity(toolName = toolName)
        dao.upsert(current.copy(enabled = enabled))
        _policies.value = _policies.value + (toolName to current.copy(enabled = enabled))
    }

    /**
     * 设置「每次都问」。
     * 破坏性工具不允许关闭——这是硬约束，UI 也不提供关闭开关。
     */
    suspend fun setRequireApproval(toolName: String, require: Boolean, risk: ToolRisk) {
        val effective = if (risk == ToolRisk.DESTRUCTIVE) true else require
        val current = _policies.value[toolName] ?: ToolPolicyEntity(toolName = toolName)
        dao.upsert(current.copy(requireApproval = effective))
        _policies.value = _policies.value + (toolName to current.copy(requireApproval = effective))
    }

    override fun isEnabled(toolName: String): Boolean = _policies.value[toolName]?.enabled ?: true

    override fun requiresApproval(tool: Tool): Boolean {
        val policy = _policies.value[tool.spec.name]
        return when (tool.risk) {
            ToolRisk.DESTRUCTIVE -> true
            ToolRisk.WRITE -> policy?.requireApproval ?: true
            ToolRisk.READ_ONLY -> policy?.requireApproval ?: false
        }
    }

    /** 该工具的「每次都问」是否可被关闭。 */
    fun isApprovalMandatory(tool: Tool): Boolean = tool.risk == ToolRisk.DESTRUCTIVE

    private fun defaultRequiresApproval(tool: Tool): Boolean = when (tool.risk) {
        ToolRisk.READ_ONLY -> false
        ToolRisk.WRITE -> true
        ToolRisk.DESTRUCTIVE -> true
    }
}
