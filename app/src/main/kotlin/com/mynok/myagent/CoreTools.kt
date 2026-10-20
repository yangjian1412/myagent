package com.mynok.myagent

import com.mynok.myagent.core.tools.ToolRegistry
import com.mynok.myagent.core.tools.builtin.NotionUnifiedTool
import com.mynok.myagent.core.tools.builtin.WebFetchTool
import com.mynok.myagent.core.tools.builtin.WebSearchTool
import com.mynok.myagent.core.tools.builtin.HttpRequestTool
import com.mynok.myagent.di.AppContainer

/**
 * core 模块内置工具的注册入口。
 *
 * 纯网络型工具（搜索、抓取、Notion、通用 HTTP）实现放在 core 里，
 * 这样它们可以在纯 JVM 环境下被单元测试覆盖。
 */
object CoreTools {

    fun registerAll(registry: ToolRegistry, container: AppContainer) {
        val http = container.http
        val secrets = container.toolSecrets
        registry.register(WebSearchTool(http, secrets = secrets))
        registry.register(WebFetchTool(http))
        registry.register(HttpRequestTool(http))
        // 三个 Notion 工具合并成一个 notion（action=search/read/create），
        // token 由「工具 → 集成密钥」配置，运行期现取
        registry.register(NotionUnifiedTool(http, secrets = secrets))
    }
}
