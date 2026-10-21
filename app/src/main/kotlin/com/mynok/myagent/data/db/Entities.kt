package com.mynok.myagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 会话。 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 当前使用的模型档案 id。 */
    val modelProfileId: String? = null,
    /** 会话级系统提示（覆盖全局）。 */
    val systemPrompt: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** 会话级上下文预算（token），null 表示用模型默认值。 */
    val contextWindowOverride: Int? = null,
    /** 当前激活的分支叶子（决定会话显示哪条分支）。 */
    val activeLeafId: String? = null,
    /** 会话级思考强度（OFF / LOW / MEDIUM / HIGH）。 */
    val reasoningEffort: String = "OFF",
    /** 是否在对话里展示模型的思考过程。 */
    val showReasoning: Boolean = true,
    /** 是否在对话里展示工具调用过程与结果。 */
    val showToolCalls: Boolean = true,
    /** 单次回复最多执行多少轮工具调用（4–32）。 */
    val maxSteps: Int = 8,
)

/**
 * 消息。
 *
 * 用 [parentId] 构成消息树：编辑历史消息 / 重新生成都会产生分支而不是破坏历史。
 */
@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("parentId"), Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String?,
    val role: String,
    /** 序列化后的 ContentPart 列表。 */
    val contentJson: String,
    /** 序列化后的 ToolCall 列表。 */
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** 钉住的消息不参与压缩与裁剪。 */
    val pinned: Boolean = false,
    /** MESSAGE / SUMMARY。 */
    val kind: String = "MESSAGE",
    val provider: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    /** 估算 token 数，列表里显示用。 */
    val tokenEstimate: Int = 0,
    /** 助手消息的思考内容。 */
    val reasoning: String = "",
)

/** 模型档案。 */
@Entity(tableName = "model_profiles")
data class ModelProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerType: String,
    val baseUrl: String,
    val modelId: String,
    /** 指向加密存储里的 key；null 表示不需要鉴权。 */
    val secretId: String? = null,
    val headersJson: String = "{}",
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    /** 是否支持把文件（PDF 等）原样发给模型。 */
    val supportsFileInput: Boolean = false,
    val supportsStreaming: Boolean = true,
    val contextWindow: Int = 32_768,
    val maxOutputTokens: Int = 4_096,
    val temperature: Double? = null,
    val topP: Double? = null,
    /** 逗号分隔的思考强度等级（OFF/LOW/MEDIUM/HIGH…），空表示不支持。 */
    val reasoningLevels: String = "",
    val extraBodyJson: String = "{}",
    val builtin: Boolean = false,
    val sortOrder: Int = 0,
)

/** MCP 服务端配置。 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    /** token 的加密存储 id。 */
    val secretId: String? = null,
    val headersJson: String = "{}",
    val enabled: Boolean = true,
    val lastConnectedAt: Long? = null,
    val lastError: String? = null,
    val toolCount: Int = 0,
)

/** 工具开关与审批策略。 */
@Entity(tableName = "tool_policies")
data class ToolPolicyEntity(
    @PrimaryKey val toolName: String,
    val enabled: Boolean = true,
    val requireApproval: Boolean = false,
    val isMcp: Boolean = false,
    val serverId: String? = null,
)

/**
 * 技能（Skill）。
 *
 * 技能真正的内容存在文件里（`SKILL.md`，兼容 Agent Skills / ClawHub 格式），
 * 这张表只是**索引 + 本地状态**：扫描技能目录后同步进来，
 * 记录启用开关、排序、来源路径，以及从 frontmatter 解析出来的元数据。
 *
 * 手工创建的技能不落文件，直接存在这张表里（[source] = BUILTIN）。
 */
@Entity(tableName = "skills")
data class SkillEntity(
    /** 技能 id，用目录名（slug）作为主键。 */
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    /** 附加到系统提示的正文（从 SKILL.md 读出来的 Markdown）。 */
    val prompt: String,
    /** 逗号分隔的工具名列表；非空表示本次会话只用这些工具。 */
    val forceTools: String = "",
    val sortOrder: Int = 0,
    /** 来源：BUILTIN=手工创建，FILE=来自技能目录里的 SKILL.md。 */
    val source: String = SOURCE_BUILTIN,
    /** 来源目录名（FILE 来源时有效）。 */
    val sourceDir: String = "",
    /** 版本号。 */
    val version: String = "",
    /** 许可证。 */
    val license: String = "",
    /** 展示用 emoji。 */
    val emoji: String = "",
    /** 主页地址。 */
    val homepage: String = "",
    /** 逗号分隔的环境变量名（ClawHub 的 requires.env）。 */
    val requiredEnv: String = "",
    /** 逗号分隔的命令行程序（手机上通常不适用）。 */
    val requiredBins: String = "",
    /** 逗号分隔的平台限制，如 macos。 */
    val requiredOs: String = "",
    /** 是否启用。停用的技能不会出现在模型可见的目录里。 */
    val enabled: Boolean = true,
    /** 文件最后修改时间，用于判断是否需要重新扫描。 */
    val updatedAt: Long = 0,
) {
    companion object {
        const val SOURCE_BUILTIN = "BUILTIN"
        const val SOURCE_FILE = "FILE"
    }
}

/**
 * 定时任务。
 *
 * 到点后按 [actionType] 执行：发通知、调一个工具，或者跑一次完整的 Agent。
 * [cron] 用 5 段 Unix 表达式，具体解析在 core 的 CronExpression。
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 5 段 cron 表达式，如 "0 8 * * *" 表示每天 8:00。 */
    val cron: String,
    /** 动作类型：NOTIFY / TOOL / AGENT。 */
    val actionType: String,
    /** 动作参数（JSON 字符串），结构随 actionType 变化。 */
    val payload: String = "{}",
    val enabled: Boolean = true,
    /** AGENT 类型用哪个模型档案。 */
    val modelProfileId: String? = null,
    /** AGENT 类型用哪个技能。 */
    val skillId: String? = null,
    /** 逗号分隔的白名单工具。AGENT 类型里这些工具视为已授权。 */
    val allowedTools: String = "",
    /** 专属会话 id（AGENT 类型的执行结果写进这里）。 */
    val conversationId: String? = null,
    val lastRunAt: Long? = null,
    /** 上次执行结果：OK / ERROR。 */
    val lastStatus: String = "",
    /** 上次执行的错误或摘要。 */
    val lastMessage: String = "",
    /** 下次触发时间。 */
    val nextRunAt: Long? = null,
    val createdAt: Long = 0,
) {
    companion object {
        const val ACTION_NOTIFY = "NOTIFY"
        const val ACTION_TOOL = "TOOL"
        const val ACTION_AGENT = "AGENT"
    }
}
