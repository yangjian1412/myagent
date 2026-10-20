package com.mynok.myagent.data.repo

import com.mynok.myagent.core.context.MessageNode
import com.mynok.myagent.core.context.MessageTree
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelProvider
import com.mynok.myagent.core.model.ProviderFactory
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.util.MyAgentJson
import com.mynok.myagent.data.crypto.SecretStore
import com.mynok.myagent.data.db.ConversationEntity
import com.mynok.myagent.data.db.McpServerEntity
import com.mynok.myagent.data.db.MessageEntity
import com.mynok.myagent.data.db.ModelProfileEntity
import com.mynok.myagent.data.db.MyAgentDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * 会话与消息的仓储。
 *
 * 消息以树的形式存储：[MessageEntity.parentId] 指向父消息，
 * 因此「编辑历史」「重新生成」都只是新增分支，不会破坏原有历史。
 */
class ConversationRepository(
    private val db: MyAgentDatabase,
    private val secretStore: SecretStore,
    private val http: Http,
) {

    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val profileDao = db.modelProfileDao()
    private val mcpDao = db.mcpServerDao()

    // ------------------------------------------------------------ 会话

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeActive()

    fun observeConversation(id: String): Flow<ConversationEntity?> = conversationDao.observe(id)

    suspend fun createConversation(
        title: String = "新会话",
        modelProfileId: String? = null,
        systemPrompt: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                modelProfileId = modelProfileId,
                systemPrompt = systemPrompt,
            ),
        )
        return id
    }

    suspend fun deleteConversation(id: String) {
        messageDao.deleteByConversation(id)
        conversationDao.delete(id)
    }

    /** 清空所有会话，消息一并删除。 */
    suspend fun clearConversations() {
        conversationDao.getAll().forEach { messageDao.deleteByConversation(it.id) }
        conversationDao.deleteAll()
    }

    suspend fun renameConversation(id: String, title: String) =
        conversationDao.rename(id, title, System.currentTimeMillis())

    suspend fun setConversationModel(id: String, profileId: String?) =
        conversationDao.setModel(id, profileId)

    suspend fun setConversationSystemPrompt(id: String, prompt: String?) =
        conversationDao.setSystemPrompt(id, prompt)

    suspend fun setConversationReasoningEffort(id: String, effort: String) =
        conversationDao.setReasoningEffort(id, effort)

    suspend fun setConversationShowReasoning(id: String, show: Boolean) =
        conversationDao.setShowReasoning(id, show)

    suspend fun setConversationShowToolCalls(id: String, show: Boolean) =
        conversationDao.setShowToolCalls(id, show)

    suspend fun setActiveLeaf(conversationId: String, leafId: String?) {
        val conv = conversationDao.get(conversationId) ?: return
        conversationDao.update(conv.copy(activeLeafId = leafId, updatedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ 消息

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeAll(conversationId)

    suspend fun getMessages(conversationId: String): List<MessageEntity> =
        messageDao.getAll(conversationId)

    /** 构建消息树。 */
    suspend fun buildTree(conversationId: String): MessageTree {
        val entities = messageDao.getAll(conversationId)
        val nodes = entities.map { Mappers.toNode(it) }
        val tree = MessageTree()
        // 先全部加入（不含 children），再按 parentId 补 children
        nodes.forEach { tree.add(it.copy(children = emptyList())) }
        val byId = entities.associateBy { it.id }
        val childMap = entities.groupBy { it.parentId }
        childMap.forEach { (parentId, children) ->
            if (parentId == null) return@forEach
            val parent = tree.get(parentId) ?: return@forEach
            tree.add(parent.copy(children = children.map { it.id }))
        }
        byId.size // keep reference used
        return tree
    }

    /** 当前激活分支的路径。 */
    suspend fun activePath(conversationId: String): List<MessageNode> {
        val conv = conversationDao.get(conversationId)
        val tree = buildTree(conversationId)
        val leafId = conv?.activeLeafId ?: tree.latestLeaf()?.id
        return if (leafId != null) tree.pathTo(leafId) else emptyList()
    }

    /** 追加一条消息，返回新消息的 id。 */
    suspend fun appendMessage(
        conversationId: String,
        parentId: String?,
        message: ChatMessage,
        kind: MessageNode.Kind = MessageNode.Kind.MESSAGE,
    ): String {
        val id = UUID.randomUUID().toString()
        val entity = Mappers.toEntity(
            message = message,
            conversationId = conversationId,
            id = id,
            parentId = parentId,
            kind = kind,
        )
        messageDao.upsert(entity)
        setActiveLeaf(conversationId, id)
        conversationDao.touch(conversationId, System.currentTimeMillis())
        return id
    }

    /**
     * 从 [sourceMessageId] 之后复制出一条新分支（父节点为 [newParentId]）。
     * 返回新分支第一个消息的 id。
     */
    suspend fun forkFrom(
        conversationId: String,
        sourceMessageId: String,
        newParentId: String?,
    ): String? {
        val entities = messageDao.getAll(conversationId)
        val byId = entities.associateBy { it.id }
        val source = byId[sourceMessageId] ?: return null
        val newId = UUID.randomUUID().toString()
        val copy = source.copy(
            id = newId,
            parentId = newParentId,
            createdAt = System.currentTimeMillis(),
        )
        messageDao.upsert(copy)
        setActiveLeaf(conversationId, newId)
        return newId
    }

    /** 编辑一条消息的内容 → 在其后产生新分支（保留原消息）。 */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newText: String,
    ): String? {
        val entities = messageDao.getAll(conversationId)
        val target = entities.firstOrNull { it.id == messageId } ?: return null
        val message = Mappers.toMessage(target).copy(
            parts = listOf(com.mynok.myagent.core.model.ContentPart.Text(newText)),
        )
        val newId = UUID.randomUUID().toString()
        messageDao.upsert(
            Mappers.toEntity(
                message = message,
                conversationId = conversationId,
                id = newId,
                parentId = target.parentId,
            ),
        )
        setActiveLeaf(conversationId, newId)
        return newId
    }

    /** 删除一条消息及其所有子节点。 */
    suspend fun deleteBranch(conversationId: String, messageId: String) {
        val entities = messageDao.getAll(conversationId)
        val ids = mutableSetOf<String>()
        fun collect(id: String) {
            if (!ids.add(id)) return
            entities.filter { it.parentId == id }.forEach { collect(it.id) }
        }
        collect(messageId)
        ids.forEach { messageDao.delete(it) }
    }

    suspend fun setPinned(messageId: String, pinned: Boolean) = messageDao.setPinned(messageId, pinned)

    // ------------------------------------------------------------ 模型档案

    fun observeProfiles(): Flow<List<ModelProfileEntity>> = profileDao.observeAll()

    suspend fun getProfile(id: String): ModelProfileEntity? = profileDao.get(id)

    suspend fun firstProfileId(): String? =
        profileDao.observeAll().first().firstOrNull()?.id

    suspend fun upsertProfile(entity: ModelProfileEntity) = profileDao.upsert(entity)

    suspend fun deleteProfile(id: String) = profileDao.delete(id)

    suspend fun profileCount(): Int = profileDao.count()

    /** 实体 → core 档案。 */
    fun toProfile(entity: ModelProfileEntity): ModelProfile = ModelProfile(
        id = entity.id,
        name = entity.name,
        providerType = runCatching { ProviderType.valueOf(entity.providerType) }
            .getOrDefault(ProviderType.OPENAI_COMPAT),
        baseUrl = entity.baseUrl,
        modelId = entity.modelId,
        secretId = entity.secretId,
        headers = Mappers.decodeMap(entity.headersJson),
        supportsTools = entity.supportsTools,
        supportsVision = entity.supportsVision,
        supportsStreaming = entity.supportsStreaming,
        contextWindow = entity.contextWindow,
        maxOutputTokens = entity.maxOutputTokens,
        temperature = entity.temperature,
        topP = entity.topP,
        reasoningEfforts = ReasoningEffort.parseList(entity.reasoningLevels),
        extraBody = runCatching {
            MyAgentJson.decodeFromString(JsonObject.serializer(), entity.extraBodyJson)
        }.getOrDefault(JsonObject(emptyMap())),
        builtin = entity.builtin,
    )

    /** 构造协议适配器（会从加密存储里取 key）。 */
    suspend fun providerFor(entity: ModelProfileEntity): ModelProvider {
        val apiKey = entity.secretId?.let { secretStore.get(it) }
        return ProviderFactory.create(toProfile(entity), apiKey, http)
    }

    // ------------------------------------------------------------ MCP

    fun observeMcpServers(): Flow<List<McpServerEntity>> = mcpDao.observeAll()

    suspend fun upsertMcpServer(entity: McpServerEntity) = mcpDao.upsert(entity)

    suspend fun deleteMcpServer(id: String) = mcpDao.delete(id)

    suspend fun setMcpStatus(id: String, at: Long?, error: String?, toolCount: Int) =
        mcpDao.setStatus(id, at, error, toolCount)

    suspend fun enabledMcpServers(): List<McpServerEntity> = mcpDao.getEnabled()

    // ------------------------------------------------------------ 技能

    fun observeSkills(): Flow<List<com.mynok.myagent.data.db.SkillEntity>> = db.skillDao().observeAll()

    suspend fun upsertSkill(skill: com.mynok.myagent.data.db.SkillEntity) = db.skillDao().upsert(skill)

    suspend fun deleteSkill(id: String) = db.skillDao().delete(id)

    suspend fun getSkill(id: String): com.mynok.myagent.data.db.SkillEntity? = db.skillDao().get(id)
}
