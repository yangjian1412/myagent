package com.mynok.myagent.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp 实现。
 *
 * SSE 需要的是「边读边回调」，所以 [streamText] 直接在 source 上按行读，
 * 而不是把整个 body 读进内存。
 */
class OkHttpHttp(
    private val client: OkHttpClient = defaultClient(),
) : Http {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val call = client.newCall(buildRequest(request))
        call.execute().use { response ->
            HttpResponse(
                code = response.code,
                headers = response.headers.toMultimap(),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse =
        withContext(Dispatchers.IO) {
            val call = client.newCall(buildRequest(request))
            // 用户点「停止」时协程会被取消，但阻塞读不会自己醒，这里主动 cancel 掉连接
            val cancelOnStop = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    // 非 2xx 通常是 JSON 错误体（配额、鉴权、参数错误），整段读出来给上层
                    // 做错误提示，不能当成 SSE 喂给解析器
                    if (!response.isSuccessful) {
                        return@withContext HttpResponse(
                            code = response.code,
                            headers = response.headers.toMultimap(),
                            body = response.body?.string().orEmpty(),
                        )
                    }
                    val source = response.body?.source()
                    if (source != null) {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            onChunk(line + "\n")
                        }
                    }
                    HttpResponse(
                        code = response.code,
                        headers = response.headers.toMultimap(),
                        body = "",
                    )
                }
            } finally {
                cancelOnStop?.dispose()
            }
        }

    override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val call = client.newCall(buildRequest(request))
            call.execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val mime = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "application/octet-stream"
                mime to java.util.Base64.getEncoder().encodeToString(bytes)
            }
        }

    private fun buildRequest(request: HttpRequest): Request {
        val body = when {
            request.body != null -> request.body.toRequestBody(
                (request.contentType ?: "application/json; charset=utf-8").toMediaType(),
            )

            request.method.equals("GET", ignoreCase = true) -> null
            else -> ByteArray(0).toRequestBody("application/json".toMediaType())
        }
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(request.method.uppercase(), body)
        return builder.build()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** 把一段字节编码成 Base64（纯 JVM 可用，不依赖 android.util）。 */
internal fun ByteArray.toBase64(): String =
    java.util.Base64.getEncoder().encodeToString(this)
