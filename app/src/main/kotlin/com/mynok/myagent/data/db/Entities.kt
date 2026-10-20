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
 * 一段可复用的指令模板 + 可选的「本次会话强制启用的工具」列表。
 * 选中后会把 [prompt] 拼进系统提示，并把 [forceTools] 里的工具临时放开。
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    /** 附加到系统提示的正文。 */
    val prompt: String,
    /** 逗号分隔的工具名列表；非空表示本次会话只用这些工具。 */
    val forceTools: String = "",
    val sortOrder: Int = 0,
)
