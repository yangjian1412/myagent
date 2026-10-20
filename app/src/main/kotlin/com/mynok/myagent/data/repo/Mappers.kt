package com.mynok.myagent.data.repo

import com.mynok.myagent.core.context.MessageNode
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.ToolCall
import com.mynok.myagent.core.util.MyAgentJson
import com.mynok.myagent.data.db.ConversationEntity
import com.mynok.myagent.data.db.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 实体与 core 模型之间的转换。 */
object Mappers {

    fun toEntity(
        message: ChatMessage,
        conversationId: String,
        id: String,
        parentId: String?,
        kind: MessageNode.Kind = MessageNode.Kind.MESSAGE,
        pinned: Boolean = false,
        tokenEstimate: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        parentId = parentId,
        role = message.role.name,
        contentJson = encodeParts(message.parts),
        toolCallsJson = message.toolCalls.takeIf { it.isNotEmpty() }
            ?.let { MyAgentJson.encodeToString(ListSerializer(ToolCall.serializer()), it) },
        toolCallId = message.toolCallId,
        toolName = message.name,
        pinned = pinned,
        kind = kind.name,
        provider = message.provider,
        modelId = message.modelId,
        createdAt = createdAt,
        tokenEstimate = tokenEstimate,
        reasoning = message.reasoning,
    )

    fun toMessage(entity: MessageEntity): ChatMessage = ChatMessage(
        role = runCatching { Role.valueOf(entity.role) }.getOrDefault(Role.USER),
        parts = decodeParts(entity.contentJson),
        name = entity.toolName,
        toolCallId = entity.toolCallId,
        toolCalls = entity.toolCallsJson?.let {
            runCatching {
                MyAgentJson.decodeFromString(ListSerializer(ToolCall.serializer()), it)
            }.getOrNull()
        }.orEmpty(),
        provider = entity.provider,
        modelId = entity.modelId,
        reasoning = entity.reasoning,
    )

    fun toNode(entity: MessageEntity): MessageNode {
        val children = mutableListOf<String>()
        return MessageNode(
            id = entity.id,
            parentId = entity.parentId,
            message = toMessage(entity),
            children = children,
            pinned = entity.pinned,
            kind = runCatching { MessageNode.Kind.valueOf(entity.kind) }
                .getOrDefault(MessageNode.Kind.MESSAGE),
            createdAt = entity.createdAt,
        )
    }

    private fun encodeParts(parts: List<ContentPart>): String =
        MyAgentJson.encodeToString(ListSerializer(ContentPart.serializer()), parts)

    private fun decodeParts(json: String): List<ContentPart> =
        runCatching {
            MyAgentJson.decodeFromString(ListSerializer(ContentPart.serializer()), json)
        }.getOrDefault(emptyList())

    /** 序列化一个字符串 map（headers / extraBody 用）。 */
    fun encodeMap(map: Map<String, String>): String = MyAgentJson.encodeToString(
        JsonObject.serializer(),
        JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }),
    )

    fun decodeMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            MyAgentJson.decodeFromString(JsonObject.serializer(), json)
                .mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
        }.getOrDefault(emptyMap())
    }

    fun conversationTitleOf(firstUserMessage: String?): String {
        val t = firstUserMessage?.trim().orEmpty()
        if (t.isEmpty()) return "新会话"
        val firstLine = t.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= 30) firstLine else firstLine.take(30) + "…"
    }
}
