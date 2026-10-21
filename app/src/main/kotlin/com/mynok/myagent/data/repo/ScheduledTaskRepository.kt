package com.mynok.myagent.data.repo

import com.mynok.myagent.data.db.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 定时任务仓储。
 */
class ScheduledTaskRepository(
    private val db: com.mynok.myagent.data.db.MyAgentDatabase,
) {

    private val dao = db.scheduledTaskDao()

    fun observeAll(): Flow<List<ScheduledTaskEntity>> = dao.observeAll()

    suspend fun get(id: String): ScheduledTaskEntity? = dao.get(id)

    suspend fun getEnabled(): List<ScheduledTaskEntity> = dao.getEnabled()

    suspend fun upsert(task: ScheduledTaskEntity) = dao.upsert(task)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.get(id)?.let { dao.upsert(it.copy(enabled = enabled)) }
    }

    /** 绑定专属会话（AGENT 类型第一次执行时创建）。 */
    suspend fun bindConversation(taskId: String, conversationId: String) {
        dao.get(taskId)?.let { dao.upsert(it.copy(conversationId = conversationId)) }
    }

    /** 记录上次执行结果。 */
    suspend fun recordRun(id: String, status: String, message: String, nextRunAt: Long?) {
        dao.get(id)?.let {
            dao.upsert(
                it.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastStatus = status,
                    lastMessage = message.take(500),
                    nextRunAt = nextRunAt,
                ),
            )
        }
    }
}
