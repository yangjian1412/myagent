package com.mynok.myagent.core.tools

import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/** 工具风险等级，决定是否需要用户二次确认。 */
enum class ToolRisk {
    /** 只读，不改任何状态。 */
    READ_ONLY,

    /** 会写入（创建/修改文件、发消息等）。 */
    WRITE,

    /** 破坏性（删除、覆盖、不可逆）。 */
    DESTRUCTIVE,
}

/** 工具执行结果。 */
data class ToolResult(
    /** 文本结果，会回灌给模型。 */
    val content: String,
    val isError: Boolean = false,
    /** 工具可能返回图片（例如拍照），供视觉模型消费。 */
    val images: List<ContentPart.Image> = emptyList(),
) {
    companion object {
        fun ok(content: String) = ToolResult(content)
        fun error(content: String) = ToolResult(content, isError = true)
    }
}

/** 一次审批请求。 */
data class ToolApproval(
    val toolName: String,
    val reason: String,
    val risk: ToolRisk,
    /** 需要展示给用户的关键参数摘要。 */
    val detail: String = "",
)

/** 审批器：返回 true 表示允许执行。 */
fun interface ToolApprover {
    suspend fun request(approval: ToolApproval): Boolean
}

/** 工具执行上下文。 */
interface ToolContext {
    val http: com.mynok.myagent.core.net.Http
    val approver: ToolApprover
    /** 工作区（App 层由 SAF 提供；core 里只作为通用能力接口）。 */
    val workspace: FileWorkspace
    /** 日志输出。 */
    fun log(message: String)
}

/** 文件工作区抽象（core 保持纯 JVM，Android 侧用 SAF 实现）。 */
interface FileWorkspace {
    /** 列出目录；path 为 "/" 表示根。 */
    suspend fun list(path: String): List<WorkspaceEntry>

    /** 读文本，超过 [maxBytes] 则截断。 */
    suspend fun readText(path: String, maxBytes: Int = 512 * 1024): String

    /** 写文本。 */
    suspend fun writeText(path: String, content: String): Unit

    suspend fun exists(path: String): Boolean

    suspend fun delete(path: String): Boolean

    /** 创建目录。 */
    suspend fun mkdir(path: String): Boolean
}

/** 工作区条目。 */
data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
)

/**
 * 一个工具。
 *
 * 内置工具和 MCP 工具都实现这个接口，因此 agent 循环完全不区分二者。
 */
interface Tool {
    /** 暴露给模型的声明。 */
    val spec: ToolSpec

    /** 风险等级。 */
    val risk: ToolRisk get() = ToolRisk.READ_ONLY

    /** 是否每次调用都需要用户确认。 */
    val requiresApproval: Boolean get() = false

    /** 执行。参数非法时应返回 [ToolResult.isError] = true 的错误说明，而不是抛异常。 */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * 工具策略。
 *
 * 决定「某个工具能不能用」以及「用之前要不要先问用户」。
 * App 层用 Room 里的 tool_policies 表实现，core 里给一个默认实现。
 */
interface ToolPolicy {

    /** 工具是否被启用。被禁用的工具对模型表现为「不可用」。 */
    fun isEnabled(toolName: String): Boolean = true

    /** 该次调用是否需要用户确认。 */
    fun requiresApproval(tool: Tool): Boolean = when (tool.risk) {
        ToolRisk.READ_ONLY -> tool.requiresApproval
        ToolRisk.WRITE -> true
        ToolRisk.DESTRUCTIVE -> true
    }

    companion object {
        /** 全部放行、只对破坏性操作询问。 */
        val Permissive: ToolPolicy = object : ToolPolicy {
            override fun requiresApproval(tool: Tool): Boolean =
                tool.requiresApproval || tool.risk == ToolRisk.DESTRUCTIVE
        }

        /** 全部放行，什么都不问。 */
        val TrustAll: ToolPolicy = object : ToolPolicy {
            override fun requiresApproval(tool: Tool): Boolean = false
        }
    }
}
