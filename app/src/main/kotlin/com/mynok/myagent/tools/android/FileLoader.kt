package com.mynok.myagent.tools.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.ContentParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 用户发送的附件加载。
 *
 * 这里只负责「把文件原样读进内存」，不判断内容该怎么发：
 * 文本类附件在发请求时由协议层转成文本块，二进制（PDF 等）
 * 才按各家的文档格式发出去，判断逻辑在 [ContentParts] 里。
 */
object FileLoader {

    /** 单个附件的最大字节数。超过就拒绝。 */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** 真正能被模型读懂的二进制类型。 */
    private const val READABLE_MIME = "application/pdf"

    /** 结果：要么拿到一个附件，要么拿到一句给用户看的话。 */
    sealed interface Result {
        /**
         * @param part 真正要发出去的内容
         * @param label chip 上显示的名字（文件名）
         * @param note 要提醒用户的话，没有则不提醒
         */
        data class Ok(
            val part: ContentPart,
            val label: String,
            val note: String? = null,
        ) : Result

        data class Failed(val message: String) : Result
    }

    /**
     * 读一个附件。
     *
     * @param context 上下文
     * @param uri 用户选中的文件
     */
    suspend fun load(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        val size = sizeOf(context, uri)

        if (size > MAX_FILE_BYTES) {
            return@withContext Result.Failed(
                "文件太大（${size / 1024 / 1024} MB），上限 ${MAX_FILE_BYTES / 1024 / 1024} MB。",
            )
        }

        // 不管是文本还是二进制，都按原样存成附件；
        // 文本类在发请求时由协议层转成文本块（见 ContentParts.asTextContent）
        val bytes = readBytes(context, uri)
            ?: return@withContext Result.Failed("读取文件失败：$name")
        if (bytes.isEmpty()) {
            return@withContext Result.Failed("文件里没有内容：$name")
        }

        val resolvedMime = mimeType.ifBlank { guessMime(extension) }
        val isTextual = ContentParts.isTextual(resolvedMime, name)
        val note = when {
            isTextual || resolvedMime == READABLE_MIME -> null
            else -> "「$name」不是文本也不是 PDF，模型多半读不出内容" +
                "（Word、Excel 这类文档现在还不能直接发）。"
        }
        Result.Ok(
            part = ContentPart.File(
                name = name,
                mimeType = resolvedMime,
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            ),
            label = name,
            note = note,
        )
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** 文件名（用户能认出来的名字）。 */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "附件"
    }

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun guessMime(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "doc" -> "application/msword"
        "ppt" -> "application/vnd.ms-powerpoint"
        "epub" -> "application/epub+zip"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
