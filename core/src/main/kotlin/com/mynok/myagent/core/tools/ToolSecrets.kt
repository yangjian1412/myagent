package com.mynok.myagent.core.tools

/**
 * 工具运行期读取的第三方密钥。
 *
 * 密钥不能写死在工具构造参数里：用户在「工具」页改了 token，正在运行的工具也要
 * 立刻看到新值。所以工具每次执行时现取，而不是启动时快照。
 *
 * 约定好的键名见 [ToolSecretKeys]。
 */
fun interface ToolSecrets {

    /** 取一个密钥；没配置返回 null。 */
    suspend fun get(name: String): String?
}

/** 工具密钥的键名（同时也是 SecretStore 里的 id 前缀）。 */
object ToolSecretKeys {
    const val NOTION = "notion"
    const val TAVILY = "tavily"
    const val BRAVE = "brave"

    /** 全部可在界面上配置的密钥，顺序即展示顺序。 */
    val all: List<String> = listOf(NOTION, TAVILY, BRAVE)

    /** 界面上的显示名。 */
    fun label(name: String): String = when (name) {
        NOTION -> "Notion Integration Token"
        TAVILY -> "Tavily API Key"
        BRAVE -> "Brave Search API Key"
        else -> name
    }

    /** 一句话说明去哪里拿。 */
    fun hint(name: String): String = when (name) {
        NOTION -> "notion.so/my-integrations 创建集成后复制 Internal Integration Token，并把页面共享给该集成"
        TAVILY -> "tavily.com 注册后获取，用于 web_search 的 tavily 提供方"
        BRAVE -> "api.search.brave.com 订阅后获取，用于 web_search 的 brave 提供方"
        else -> ""
    }
}
