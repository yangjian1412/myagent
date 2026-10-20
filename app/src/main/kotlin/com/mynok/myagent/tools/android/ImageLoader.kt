package com.mynok.myagent.tools.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.mynok.myagent.core.model.ContentPart
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 图片加载与压缩。
 *
 * 直接把原图 Base64 塞进请求体会迅速撑爆上下文，所以：
 * 1. 先按最大边等比缩放
 * 2. JPEG 质量 85
 * 3. 再给一份小的（长边 512）供低分辨率模型用
 */
object ImageLoader {

    const val LARGE_MAX_EDGE = 1568
    const val SMALL_MAX_EDGE = 512

    suspend fun load(context: Context, uri: Uri): ContentPart.Image? {
        val bitmap = decode(context, uri) ?: return null
        val large = scale(bitmap, LARGE_MAX_EDGE)
        val out = ByteArrayOutputStream()
        large.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.jpg"
        return ContentPart.Image(
            mimeType = "image/jpeg",
            base64 = base64,
            displayName = name,
        )
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        // 先取尺寸，只按尺寸采样解码，避免 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > LARGE_MAX_EDGE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    private fun scale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
