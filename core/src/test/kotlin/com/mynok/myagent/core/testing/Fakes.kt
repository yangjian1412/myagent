package com.mynok.myagent.core.testing

import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.tools.FileWorkspace
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolApprover
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.WorkspaceEntry

/** 记录请求并返回固定响应的假 Http。 */
class FakeHttp(
    private val response: HttpResponse = HttpResponse(200),
    private val streamBody: String = "",
) : Http {

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return response
    }

    override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse {
        requests += request
        if (streamBody.isNotEmpty()) onChunk(streamBody)
        return response
    }

    override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> = "" to ""
}

/** 空的工具工作区。 */
object FakeWorkspace : FileWorkspace {
    override suspend fun list(path: String): List<WorkspaceEntry> = emptyList()
    override suspend fun readText(path: String, maxBytes: Int): String = ""
    override suspend fun writeText(path: String, content: String) = Unit
    override suspend fun exists(path: String): Boolean = false
    override suspend fun delete(path: String): Boolean = false
    override suspend fun mkdir(path: String): Boolean = true
}

/** 测试用的工具上下文。 */
class FakeToolContext(
    override val http: Http = FakeHttp(),
    override val workspace: FileWorkspace = FakeWorkspace,
    /** 审批结果。 */
    private val approve: Boolean = true,
) : ToolContext {

    val logs = mutableListOf<String>()

    /** 被请求过的审批记录。 */
    val approvals = mutableListOf<ToolApproval>()

    override val approver: ToolApprover = object : ToolApprover {
        override suspend fun request(approval: ToolApproval): Boolean {
            approvals += approval
            return approve
        }
    }

    override fun log(message: String) {
        logs += message
    }
}
