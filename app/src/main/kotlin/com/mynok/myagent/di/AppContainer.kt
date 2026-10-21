package com.mynok.myagent.di

import android.content.Context
import com.mynok.myagent.core.agent.AgentLoop
import com.mynok.myagent.core.context.ContextEngine
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.OkHttpHttp
import com.mynok.myagent.core.tools.ToolRegistry
import com.mynok.myagent.data.crypto.SecretStore
import com.mynok.myagent.data.db.MyAgentDatabase
import com.mynok.myagent.data.repo.ConversationRepository
import com.mynok.myagent.tools.android.MediaCaptureCoordinator
import com.mynok.myagent.tools.android.SafFileWorkspace

/**
 * 手写依赖容器。
 *
 * 不引 Hilt 是为了少一个注解处理器，减少构建风险——这个 App 的依赖关系很简单。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: MyAgentDatabase by lazy { MyAgentDatabase.get(appContext) }

    val secretStore: SecretStore by lazy { SecretStore(appContext) }

    val http: Http by lazy { OkHttpHttp() }

    val repository: ConversationRepository by lazy {
        ConversationRepository(database, secretStore, http)
    }

    val workspace: SafFileWorkspace by lazy { SafFileWorkspace(appContext) }

    val workspaceStore: com.mynok.myagent.tools.WorkspaceStore by lazy {
        com.mynok.myagent.tools.WorkspaceStore(appContext)
    }

    val mcpManager: com.mynok.myagent.tools.McpManager by lazy {
        com.mynok.myagent.tools.McpManager(repository, secretStore, http, toolRegistry)
    }

    val toolRegistry: ToolRegistry by lazy { ToolRegistry() }

    val settingsStore: com.mynok.myagent.data.repo.SettingsStore by lazy {
        com.mynok.myagent.data.repo.SettingsStore(appContext)
    }

    val toolPolicyStore: com.mynok.myagent.data.repo.ToolPolicyStore by lazy {
        com.mynok.myagent.data.repo.ToolPolicyStore(database)
    }

    /** 工具运行期读取的第三方密钥（Notion / Tavily / Brave），存在加密的 SecretStore 里。 */
    val toolSecrets: com.mynok.myagent.core.tools.ToolSecrets by lazy {
        com.mynok.myagent.core.tools.ToolSecrets { name ->
            runCatching { secretStore.get("tool:$name") }.getOrNull()
        }
    }

    val agentLoop: AgentLoop by lazy {
        AgentLoop(toolRegistry, ContextEngine(), policy = toolPolicyStore)
    }

    val approvalCoordinator: ApprovalCoordinator by lazy { ApprovalCoordinator() }

    /** 拍照工具与 Activity 之间的桥梁。 */
    val mediaCapture: MediaCaptureCoordinator by lazy {
        MediaCaptureCoordinator()
    }
}
