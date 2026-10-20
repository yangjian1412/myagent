package com.mynok.myagent.di

import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.tools.FileWorkspace
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolApprover
import com.mynok.myagent.core.tools.ToolContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次待用户确认的工具调用。 */
data class PendingApproval(
    val approval: ToolApproval,
    val id: Long,
)

/**
 * 工具审批协调器。
 *
 * 工具在协程里挂起等用户点「允许 / 拒绝」，UI 侧订阅 [pending] 弹出确认框。
 */
class ApprovalCoordinator : ToolApprover {

    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null
    private var counter = 0L

    override suspend fun request(approval: ToolApproval): Boolean {
        val d = CompletableDeferred<Boolean>()
        synchronized(this) {
            counter++
            deferred = d
            _pending.value = PendingApproval(approval, counter)
        }
        val result = d.await()
        synchronized(this) {
            if (deferred === d) {
                deferred = null
                _pending.value = null
            }
        }
        return result
    }

    fun resolve(approved: Boolean) {
        synchronized(this) { deferred?.complete(approved) }
    }
}

/** Android 侧的 [ToolContext]。 */
class AndroidToolContext(
    override val http: Http,
    override val workspace: FileWorkspace,
    override val approver: ToolApprover,
    private val logger: (String) -> Unit = {},
) : ToolContext {
    override fun log(message: String) {
        logger(message)
    }
}
