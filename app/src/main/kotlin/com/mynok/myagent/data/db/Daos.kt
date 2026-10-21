package com.mynok.myagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET modelProfileId = :profileId WHERE id = :id")
    suspend fun setModel(id: String, profileId: String?)

    @Query("UPDATE conversations SET systemPrompt = :prompt WHERE id = :id")
    suspend fun setSystemPrompt(id: String, prompt: String?)

    @Query("UPDATE conversations SET reasoningEffort = :effort WHERE id = :id")
    suspend fun setReasoningEffort(id: String, effort: String)

    @Query("UPDATE conversations SET showReasoning = :show WHERE id = :id")
    suspend fun setShowReasoning(id: String, show: Boolean)

    @Query("UPDATE conversations SET showToolCalls = :show WHERE id = :id")
    suspend fun setShowToolCalls(id: String, show: Boolean)

    @Query("UPDATE conversations SET maxSteps = :steps WHERE id = :id")
    suspend fun setMaxSteps(id: String, steps: Int)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeAll(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getAll(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun observeCount(conversationId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE messages SET contentJson = :contentJson WHERE id = :id")
    suspend fun updateContent(id: String, contentJson: String)

    @Query("UPDATE messages SET parentId = :parentId WHERE id = :id")
    suspend fun setParent(id: String, parentId: String?)
}

@Dao
interface ModelProfileDao {

    @Query("SELECT * FROM model_profiles ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM model_profiles WHERE id = :id")
    suspend fun get(id: String): ModelProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ModelProfileEntity)

    @Query("DELETE FROM model_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM model_profiles")
    suspend fun count(): Int
}

@Dao
interface McpServerDao {

    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun get(id: String): McpServerEntity?

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1")
    suspend fun getEnabled(): List<McpServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE mcp_servers SET lastConnectedAt = :at, lastError = :error, toolCount = :toolCount WHERE id = :id",
    )
    suspend fun setStatus(id: String, at: Long?, error: String?, toolCount: Int)
}

@Dao
interface ToolPolicyDao {

    @Query("SELECT * FROM tool_policies")
    fun observeAll(): Flow<List<ToolPolicyEntity>>

    @Query("SELECT * FROM tool_policies WHERE toolName = :name")
    suspend fun get(name: String): ToolPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: ToolPolicyEntity)

    @Query("DELETE FROM tool_policies WHERE toolName = :name")
    suspend fun delete(name: String)
}

@Dao
interface SkillDao {

    @Query("SELECT * FROM skills ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun get(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY sortOrder ASC, name ASC")
    fun observeEnabled(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1")
    suspend fun getEnabled(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE source = 'FILE'")
    suspend fun getFileSkills(): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM skills WHERE source = 'FILE'")
    suspend fun deleteFileSkills()
}

@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun get(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
