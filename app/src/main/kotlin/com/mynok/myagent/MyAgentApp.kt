package com.mynok.myagent

import android.app.Application
import com.mynok.myagent.di.AppContainer
import com.mynok.myagent.tools.android.AppTools
import com.mynok.myagent.tools.android.AudioRecorderTool
import com.mynok.myagent.tools.android.CalendarTools
import com.mynok.myagent.tools.android.CameraTool
import com.mynok.myagent.tools.android.ClipboardTool
import com.mynok.myagent.tools.android.ContactTools
import com.mynok.myagent.tools.android.FileTools
import com.mynok.myagent.tools.android.LocationTools
import com.mynok.myagent.tools.android.SkillListTool
import com.mynok.myagent.tools.android.SkillReadTool
import com.mynok.myagent.tools.android.SkillWriteTool
import com.mynok.myagent.tools.android.SystemTools
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
            // 恢复技能目录授权并重新扫描一次索引
            container.skillRepository.restoreDirectory()
            container.skillRepository.syncFromFiles()
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
        registry.register(ClipboardTool(ctx))

        // —— 系统能力：闹钟、日历、联系人、定位、电话、音量、手电筒、拍照、录音 ——
        registry.register(SystemTools.alarm(ctx))
        registry.register(SystemTools.volume(ctx))
        registry.register(SystemTools.torch(ctx))
        registry.register(SystemTools.phone(ctx))
        registry.register(CalendarTools.calendar(ctx))
        registry.register(ContactTools.contacts(ctx))
        registry.register(LocationTools.location(ctx))
        registry.register(CameraTool(ctx, container.mediaCapture))
        registry.register(AudioRecorderTool(ctx))

        val fileTools = FileTools(container.workspace)
        registry.register(fileTools.list())
        registry.register(fileTools.read())
        registry.register(fileTools.write())
        registry.register(fileTools.search())

        // —— 技能：模型按需读取正文，也可以自己写新技能 ——
        registry.register(SkillReadTool(container.skillRepository))
        registry.register(SkillListTool(container.skillRepository))
        registry.register(SkillWriteTool(container.skillRepository))
    }
}
