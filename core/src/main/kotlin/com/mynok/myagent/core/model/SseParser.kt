package com.mynok.myagent.core.model

/**
 * SSE（Server-Sent Events）解析器。
 *
 * 三家协议都用 `text/event-stream`，格式一致：
 * ```
 * event: message
 * data: {"a":1}
 * <空行>
 * ```
 * 但也允许只有 `data:` 没有 `event:`，所以这里同时保留 event 名和 data。
 *
 * 不是线程安全的：每次请求创建一个新实例。
 */
class SseParser {

    private val eventBuffer = StringBuilder()
    private val dataBuffer = StringBuilder()
    private var eventName: String? = null

    /** 喂入一段原始文本，返回解析出的完整事件。 */
    fun onChunk(chunk: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        // 按行处理，保留末尾未完成的半行
        var start = 0
        while (true) {
            val idx = chunk.indexOf('\n', start)
            if (idx < 0) {
                val rest = chunk.substring(start)
                if (rest.isNotEmpty()) pendingLine.append(rest)
                break
            }
            val line = pendingLine.toString() + chunk.substring(start, idx)
            pendingLine.setLength(0)
            start = idx + 1
            val event = processLine(line)
            if (event != null) out += event
        }
        return out
    }

    private val pendingLine = StringBuilder()

    private fun processLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            // 事件分隔符：只有累积了 data 才是完整事件
            if (dataBuffer.isEmpty()) {
                eventName = null
                return null
            }
            val data = dataBuffer.toString().removeSuffix("\n")
            val name = eventName
            dataBuffer.setLength(0)
            eventName = null
            return SseEvent(name, data)
        }
        if (line.startsWith(":")) return null // 注释 / 心跳
        val colon = line.indexOf(':')
        val field: String
        val value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1).removePrefix(" ")
        }
        when (field) {
            "event" -> eventName = value
            "data" -> dataBuffer.append(value).append('\n')
            "id", "retry" -> Unit // 忽略
            else -> Unit
        }
        return null
    }

    /** 流结束时调用，兜底吐出未以空行结束的最后一条数据。 */
    fun finish(): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        if (pendingLine.isNotEmpty()) {
            processLine(pendingLine.toString())
            pendingLine.setLength(0)
        }
        if (dataBuffer.isNotEmpty()) {
            out += SseEvent(eventName, dataBuffer.toString().removeSuffix("\n"))
            dataBuffer.setLength(0)
            eventName = null
        }
        return out
    }
}

/** 一条 SSE 事件。 */
data class SseEvent(
    val name: String?,
    val data: String,
)
