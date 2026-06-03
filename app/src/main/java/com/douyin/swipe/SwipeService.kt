package com.douyin.swipe

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

/**
 * 无障碍服务 — 主控中心
 *
 * 协调：MediaProjection 截屏 → RingDetector 检测 → HumanSwipe 上滑
 */
class SwipeService : AccessibilityService() {

    companion object {
        private const val TAG = "SwipeService"
        const val CHANNEL_ID = "douyin_swipe"
        const val NOTIFICATION_ID = 1
        private const val CHECK_INTERVAL_MS = 1500L

        /** 供 MainActivity 传入 MediaProjection intent */
        var mediaProjectionIntent: Intent? = null
        var resultCode: Int = 0
    }

    private lateinit var mainHandler: Handler
    private var bgHandler: Handler? = null
    private var bgThread: HandlerThread? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isRunning = false
    private var isSwiping = false  // 防止并发上滑

    private var currentBitmap: Bitmap? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || isSwiping) {
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
                return
            }
            // 在后台线程执行帧处理
            bgHandler?.post { processFrame() }
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        mainHandler = Handler(Looper.getMainLooper())

        // 后台线程
        bgThread = HandlerThread("DetectThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        // 获取屏幕尺寸
        val metrics = resources.displayMetrics
        RingDetector.screenWidth = metrics.widthPixels
        RingDetector.screenHeight = metrics.heightPixels
        HumanSwipe.screenWidth = metrics.widthPixels
        HumanSwipe.screenHeight = metrics.heightPixels
        Log.d(TAG, "screen=${metrics.widthPixels}x${metrics.heightPixels}")

        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_CAPTURE") {
            startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onDestroy()
    }

    // ===== 启动/停止 =====

    fun startCapture() {
        val intent = mediaProjectionIntent ?: run {
            Log.e(TAG, "no MediaProjection intent")
            return
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, intent)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!isRunning) return@setOnImageAvailableListener
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                try {
                    currentBitmap?.recycle()
                    currentBitmap = imageToBitmap(image)
                } catch (e: Exception) {
                    Log.e(TAG, "imageToBitmap: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, mainHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DouyinSwipe",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        startForeground(NOTIFICATION_ID, buildNotification())

        isRunning = true
        RingDetector.fullReset()
        RingDetector.lastSwipeTime = System.currentTimeMillis()
        mainHandler.post(checkRunnable)

        Log.d(TAG, "capture started ${width}x${height}")
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacks(checkRunnable)

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        currentBitmap?.recycle()
        currentBitmap = null
        RingDetector.fullReset()

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "stopped")
    }

    // ===== 帧处理 (后台线程) =====

    private fun processFrame() {
        val bitmap = currentBitmap ?: return

        try {
            val shouldSwipe = RingDetector.processFrame(bitmap)
            if (shouldSwipe) {
                isSwiping = true
                mainHandler.post {
                    HumanSwipe.perform(this) { success ->
                        Log.d(TAG, if (success) "swipe OK" else "swipe FAIL")
                        isSwiping = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: ${e.message}", e)
        }
    }

    // ===== 工具 =====

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "抖音红包助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "自动检测红包金环并上滑"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("抖音红包助手")
            .setContentText("正在监控红包金环...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
