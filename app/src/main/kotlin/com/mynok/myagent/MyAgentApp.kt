package com.mynok.myagent

import android.app.Application
import com.mynok.myagent.di.AppContainer
import com.mynok.myagent.tools.android.AppTools
import com.mynok.myagent.tools.android.ClipboardTool
import com.mynok.myagent.tools.android.FileTools
import com.mynok.myagent.tools.android.ScreenshotTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerBuiltInTools()
        // 恢复之前授权过的目录，并加载工具策略
        appScope.launch {
            container.workspaceStore.load(container.workspace)
            container.toolPolicyStore.load()
            container.toolPolicyStore.syncTools(container.toolRegistry.all())
        }
    }

    private fun registerBuiltInTools() {
        val registry = container.toolRegistry
        val ctx = this

        // —— 纯网络型（core 模块实现）——
        CoreTools.registerAll(registry, container)

        // —— Android 能力型 ——
        registry.register(AppTools.listApps(ctx))
        registry.register(AppTools.launchApp(ctx))
        registry.register(AppTools.appInfo(ctx))
        registry.register(AppTools.openUrl(ctx))
        registry.register(AppTools.sendIntent(ctx))
        registry.register(AppTools.deviceInfo(ctx))
        registry.register(ScreenshotTool())
        registry.register(ClipboardTool(ctx))

        val fileTools = FileTools(container.workspace)
        registry.register(fileTools.list())
        registry.register(fileTools.read())
        registry.register(fileTools.write())
        registry.register(fileTools.search())
    }
}
