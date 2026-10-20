package com.mynok.myagent.tools.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mynok.myagent.MainActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 屏幕捕获的前台服务。
 *
 * Android 要求 MediaProjection 必须在用户授权后、且由前台服务持有，
 * 否则拿不到帧。
 */
class ProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                if (data != null) {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val p = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
                    projection = p
                    p?.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                stopEverything()
                            }
                        },
                        null,
                    )
                }
            }

            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun stopEverything() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        reader = null
        projection = null
    }

    /** 抓一帧屏幕。 */
    suspend fun capture(): Bitmap? {
        val p = projection ?: return null
        val metrics = displayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = p.createVirtualDisplay(
            "myagent-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null,
        )
        reader = imageReader
        virtualDisplay = display

        return try {
            val bitmap = suspendCancellableCoroutine { cont ->
                val listener = object : ImageReader.OnImageAvailableListener {
                    override fun onImageAvailable(r: ImageReader) {
                        val image: Image? = r.acquireLatestImage()
                        if (image == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        try {
                            val plane = image.planes[0]
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val w = image.width
                            val h = image.height
                            val pixels = IntArray(w * h)
                            val buffer = plane.buffer
                            val rowBytes = rowStride
                            val row = ByteArray(rowBytes)
                            for (y in 0 until h) {
                                buffer.position(y * rowBytes)
                                val toRead = minOf(rowBytes, buffer.remaining())
                                buffer.get(row, 0, toRead)
                                var offset = 0
                                for (x in 0 until w) {
                                    val r = row[offset].toInt() and 0xFF
                                    val g = row[offset + 1].toInt() and 0xFF
                                    val b = row[offset + 2].toInt() and 0xFF
                                    pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                    offset += pixelStride
                                }
                            }
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                            if (cont.isActive) cont.resume(bmp)
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            image.close()
                        }
                    }
                }
                imageReader.setOnImageAvailableListener(listener, null)
            }
            bitmap
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader.close() }
            virtualDisplay = null
            reader = null
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕共享",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "AI Agent 截取屏幕时保持服务存活"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyAgent 正在共享屏幕")
            .setContentText("用于 AI 读取当前屏幕内容")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "myagent_projection"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.mynok.myagent.projection.START"
        const val ACTION_STOP = "com.mynok.myagent.projection.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile
        var instance: ProjectionService? = null
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProjectionService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
