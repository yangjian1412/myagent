package com.mynok.myagent.tools

import com.mynok.myagent.core.mcp.McpClient
import com.mynok.myagent.core.mcp.McpServerConfig
import com.mynok.myagent.core.mcp.McpTool
import com.mynok.myagent.core.mcp.McpToolFactory
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.tools.ToolRegistry
import com.mynok.myagent.data.crypto.SecretStore
import com.mynok.myagent.data.db.McpServerEntity
import com.mynok.myagent.data.repo.ConversationRepository
import com.mynok.myagent.data.repo.Mappers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MCP 同步结果。 */
data class McpSyncResult(
    val connected: List<String>,
    val failed: List<Pair<String, String>>,
    val toolCount: Int,
)

/**
 * MCP 接入管理。
 *
 * 负责把已启用的 MCP 服务连上，并把它们的工具注册进全局 [ToolRegistry]，
 * 让 MCP 工具和内置工具在 agent 循环里完全等价。
 */
class McpManager(
    private val repository: ConversationRepository,
    private val secretStore: SecretStore,
    private val http: Http,
    private val registry: ToolRegistry,
) {

    /** 每个服务注册进去的工具名，用于重新同步时先摘掉旧的。 */
    private val registered = mutableMapOf<String, List<String>>()

    /** 已建立的客户端，保持会话（Streamable HTTP 有 session id）。 */
    private val clients = mutableMapOf<String, McpClient>()

    suspend fun sync(): McpSyncResult = withContext(Dispatchers.IO) {
        // 先摘掉上一轮的 MCP 工具
        registered.values.flatten().forEach { registry.unregister(it) }
        registered.clear()
        clients.values.forEach { runCatching { it.disconnect() } }
        clients.clear()

        val connected = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        var toolCount = 0

        repository.enabledMcpServers().forEach { entity ->
            val result = connectOne(entity)
            result.onSuccess { count ->
                connected += entity.name
                toolCount += count
            }.onFailure { e ->
                failed += entity.name to (e.message ?: e::class.simpleName.orEmpty())
                repository.setMcpStatus(entity.id, System.currentTimeMillis(), e.message, 0)
            }
        }
        McpSyncResult(connected, failed, toolCount)
    }

    private suspend fun connectOne(entity: McpServerEntity): Result<Int> = runCatching {
        val token = entity.secretId?.let { secretStore.get(it) }
        val config = McpServerConfig(
            id = entity.id,
            name = entity.name,
            url = entity.url,
            bearerToken = token,
            headers = Mappers.decodeMap(entity.headersJson),
        )
        val client = McpClient(config, http)
        client.connect()
        val tools: List<McpTool> = McpToolFactory.createAll(client, client.listTools())
        val names = tools.map { registry.register(it) }
        registered[entity.id] = names
        clients[entity.id] = client
        repository.setMcpStatus(entity.id, System.currentTimeMillis(), null, tools.size)
        tools.size
    }

    /** 断开所有连接。 */
    suspend fun disconnectAll() = withContext(Dispatchers.IO) {
        clients.values.forEach { runCatching { it.disconnect() } }
        clients.clear()
        registered.values.flatten().forEach { registry.unregister(it) }
        registered.clear()
    }
}
