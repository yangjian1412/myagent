package com.mynok.myagent.tools.android

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把「工具执行」和「Activity 的拍照结果」接起来。
 *
 * 工具没有 Activity，MainActivity 在 onCreate 时注册 launcher；
 * 拍照工具挂起等待结果，用户取消或 Activity 销毁时返回 null。
 */
class MediaCaptureCoordinator {

    private var launcher: ((Uri) -> Unit)? = null
    private var pending: CompletableDeferred<Uri?>? = null

    fun attach(launch: ((Uri) -> Unit)?) {
        launcher = launch
        if (launch == null) resolve(null)
    }

    suspend fun capture(uri: Uri): Uri? {
        val launch = launcher ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        withContext(Dispatchers.Main) { launch(uri) }
        return try {
            // 给用户足够时间拍照；应用被切走太久也不会永远挂住
            withTimeoutOrNull(180_000) { deferred.await() }
        } finally {
            pending = null
        }
    }

    fun resolve(uri: Uri?) {
        val deferred = pending
        pending = null
        if (deferred != null && !deferred.isCompleted) deferred.complete(uri)
    }
}
