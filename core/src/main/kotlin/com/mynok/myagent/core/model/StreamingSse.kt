package com.mynok.myagent.core.model

import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 边读边解析 SSE。
 *
 * [Http.streamText] 的 onChunk 是同步回调，不能在里面 emit（会破坏 Flow 的上下文
 * 约定）；如果退一步先把整段响应缓存起来再解析，界面就会「一直转圈，最后一次性
 * 蹦出全文」。所以这里把读和解析拆成两个协程：读协程只管往无界通道里塞原始文本，
 * 调用方在自己的协程里逐条消费事件，真正的增量输出。
 *
 * 非 2xx 的响应体由 [Http.streamText] 直接放进 [HttpResponse.body]，不会产生事件，
 * 调用方拿到返回后自己判断状态码并抛错。
 */
internal suspend fun Http.streamSseEvents(
    request: HttpRequest,
    onEvent: suspend (SseEvent) -> Unit,
): HttpResponse = coroutineScope {
    val chunks = Channel<String>(Channel.UNLIMITED)
    var response: HttpResponse? = null

    val reader = launch {
        try {
            response = streamText(request) { chunk -> chunks.trySend(chunk) }
            chunks.close()
        } catch (t: Throwable) {
            chunks.close(t)
        }
    }

    val parser = SseParser()
    while (true) {
        val next = chunks.receiveCatching()
        if (next.isClosed) {
            next.exceptionOrNull()?.let { throw it }
            break
        }
        val chunk = next.getOrNull() ?: continue
        parser.onChunk(chunk).forEach { onEvent(it) }
    }
    // 读协程已经把通道关掉了，join 只是等它彻底结束，顺便保证 response 可见
    reader.join()
    // 兜底：最后一条 data 可能没有以空行结尾
    parser.finish().forEach { onEvent(it) }

    response ?: throw ModelException("HTTP 请求没有返回响应")
}
