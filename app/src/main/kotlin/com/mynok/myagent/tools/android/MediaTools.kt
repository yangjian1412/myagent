package com.mynok.myagent.tools.android

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.core.content.FileProvider
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 拍照：调用系统相机，拍完把照片作为图片结果交给视觉模型。
 *
 * 不申请 CAMERA 权限——ACTION_IMAGE_CAPTURE 由系统相机应用完成拍摄。
 */
class CameraTool(
    private val context: Context,
    private val coordinator: MediaCaptureCoordinator,
) : Tool {

    override val spec = ToolSpec(
        name = "take_photo",
        description = "打开系统相机拍一张照片，并把照片返回给视觉模型。" +
            "只在用户明确要求拍照、看某样东西时使用；用户取消则不会产生图片。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
            put("required", buildJsonArray { })
        },
    )

    override val risk = ToolRisk.READ_ONLY

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } catch (e: Exception) {
                return@withContext ToolResult.error(
                    "无法创建相机临时文件：${e.message ?: e.javaClass.simpleName}",
                )
            }

            val resultUri = try {
                coordinator.capture(uri)
            } catch (e: Exception) {
                file.delete()
                return@withContext ToolResult.error(
                    "打开系统相机失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (resultUri == null) {
                file.delete()
                return@withContext ToolResult.ok("拍照已取消，没有生成照片。")
            }

            try {
                val bitmap = decode(file)
                    ?: return@withContext ToolResult.error("相机没有返回可读取的照片。")
                val scaled = scaleDown(bitmap, 1568)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                ToolResult(
                    content = "已拍照（${scaled.width}x${scaled.height}）。",
                    images = listOf(
                        ContentPart.Image(
                            mimeType = "image/jpeg",
                            base64 = base64,
                            displayName = "photo.jpg",
                        ),
                    ),
                )
            } catch (e: Exception) {
                ToolResult.error("处理照片失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                file.delete()
            }
        }

    private fun decode(file: File): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
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

/**
 * 录音：录固定时长，保存到 App 专属外部目录。
 *
 * 模型目前不能直接“听”音频，所以结果里会明确说明；录音文件可被文件工具或用户后续使用。
 */
class AudioRecorderTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "record_audio",
        description = "用麦克风录制一段音频并保存为本地文件。seconds 默认 10，范围 1–60。" +
            "只在用户明确要求录音时使用；需要麦克风权限。注意：当前模型不能直接听音频。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "录音时长，1–60 秒，默认 10")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "可选的文件名前缀")
                })
            })
            put("required", buildJsonArray { })
        },
    )

    override val risk = ToolRisk.WRITE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                return@withContext ToolResult.error(permissionHint("麦克风"))
            }
            val seconds = (args.intArg("seconds", "duration") ?: 10).coerceIn(1, 60)
            val label = args.stringArg("label", "name")
                ?.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                ?.take(24)
                ?.takeIf { it.isNotBlank() }
                ?: "rec"
            val baseDir = context.getExternalFilesDir("recordings")
                ?: File(context.filesDir, "recordings")
            baseDir.mkdirs()
            val file = File(baseDir, "${label}_${System.currentTimeMillis()}.m4a")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                delay(seconds * 1000L)
                recorder.stop()
            } catch (e: Exception) {
                runCatching { recorder.reset() }
                file.delete()
                return@withContext ToolResult.error(
                    "录音失败：${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                runCatching { recorder.release() }
            }

            if (!file.exists() || file.length() <= 0) {
                return@withContext ToolResult.error("录音结束，但没有生成有效的音频文件。")
            }
            ToolResult.ok(
                "已录制 ${seconds} 秒音频，保存到：${file.absolutePath}\n" +
                    "大小：${file.length() / 1024} KB。当前模型不能直接听音频，" +
                    "可以请用户播放，或用文件工具把它转存/发送到别处。",
            )
        }
}
