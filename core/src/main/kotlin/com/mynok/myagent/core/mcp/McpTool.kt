package com.mynok.myagent.core.mcp

import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * 把一个 MCP 工具包装成我们的 [Tool]。
 *
 * 这样 agent 循环完全不需要区分内置工具和 MCP 工具：声明照常给模型，
 * 执行时转成 `tools/call`，返回的 content 再转回 [ToolResult]。
 */
class McpTool(
    private val client: McpClient,
    /** server 侧的工具定义。 */
    val toolInfo: McpToolInfo,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = McpToolFactory.qualifyName(client.serverConfig, toolInfo.name),
        description = toolInfo.description?.takeIf { it.isNotBlank() }
            ?: "MCP 工具 ${toolInfo.name}",
        parameters = toolInfo.effectiveSchema,
    )

    /** MCP 工具的风险未知，默认按只读处理。 */
    override val risk: ToolRisk get() = ToolRisk.READ_ONLY

    override val requiresApproval: Boolean get() = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = try {
        client.callTool(toolInfo.name, args)
    } catch (e: McpException) {
        ToolResult.error("MCP 工具 ${toolInfo.name} 调用失败：${e.message}")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.error("MCP 工具 ${toolInfo.name} 调用异常：${e.message ?: e::class.simpleName}")
    }
}

/** MCP 工具的构造与命名规则。 */
object McpToolFactory {

    /**
     * 限定名：`server__tool`。
     *
     * server 部分用 [McpServerConfig.prefix]（显式前缀优先，否则 server 名的 slug），
     * 工具部分保留原名，只把不能出现在工具名里的字符换成 `_`。
     */
    fun qualifyName(config: McpServerConfig, toolName: String): String =
        "${config.prefix}__${sanitizeToolName(toolName)}"

    /** 工具名前缀：显式配置优先，否则用 server 名的 slug。 */
    fun prefixOf(config: McpServerConfig): String = config.prefix

    /** 小写化，非字母数字换成 `_`。 */
    fun slug(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            builder.append(if (ch.isLetterOrDigit()) ch.lowercaseChar() else '_')
        }
        return builder.toString().trim('_').ifEmpty { "server" }
    }

    /** 包一个工具。 */
    fun create(client: McpClient, toolInfo: McpToolInfo): McpTool = McpTool(client, toolInfo)

    /** 包一批工具。 */
    fun createAll(client: McpClient, tools: List<McpToolInfo>): List<McpTool> =
        tools.map { create(client, it) }

    /** 工具名只允许字母数字和 `-` / `_`，其余换成 `_`。 */
    private fun sanitizeToolName(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-'
            builder.append(if (ok) ch else '_')
        }
        return builder.toString().ifEmpty { "tool" }
    }
}
