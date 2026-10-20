package com.mynok.myagent.core.net

import kotlinx.coroutines.flow.Flow

/** HTTP 请求。 */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    val timeoutSeconds: Long = 60,
)

/** HTTP 响应。 */
data class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** HTTP 抽象，方便在测试里替换成假实现。 */
interface Http {

    suspend fun execute(request: HttpRequest): HttpResponse

    /**
     * 流式读取响应体。
     * @param onChunk 收到一段文本时回调（用于 SSE 增量解析）
     */
    suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse

    /** 下载二进制内容（Base64）。 */
    suspend fun downloadBase64(request: HttpRequest): Pair<String, String>
}

/** 简单的阻塞式 HTTP 实现，专供非挂起场景（如同步下载）使用。 */
fun Http.blockingExecute(request: HttpRequest): HttpResponse =
    kotlinx.coroutines.runBlocking { execute(request) }

/** 便捷方法：GET 并返回 body。 */
suspend fun Http.getText(url: String, headers: Map<String, String> = emptyMap()): String =
    execute(HttpRequest(url = url, headers = headers)).body
