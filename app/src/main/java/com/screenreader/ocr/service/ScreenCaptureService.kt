package com.screenreader.ocr.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.screenreader.ocr.MainActivity
import com.screenreader.ocr.R
import com.screenreader.ocr.ScreenReaderApp
import com.screenreader.ocr.data.AppDatabase
import com.screenreader.ocr.data.OcrTextEntity
import com.screenreader.ocr.ocr.DuplicateDetector
import com.screenreader.ocr.ocr.OcrProcessor
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val VIRTUAL_DISPLAY_NAME = "ScreenReaderCapture"

        const val ACTION_START = "com.screenreader.ocr.action.START"
        const val ACTION_STOP = "com.screenreader.ocr.action.STOP"
        const val ACTION_UPDATE_REGION = "com.screenreader.ocr.action.UPDATE_REGION"
        const val ACTION_PAUSE = "com.screenreader.ocr.action.PAUSE"
        const val ACTION_RESUME = "com.screenreader.ocr.action.RESUME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_CAPTURE_MODE = "capture_mode"
        const val EXTRA_REGION_LEFT = "region_left"
        const val EXTRA_REGION_TOP = "region_top"
        const val EXTRA_REGION_RIGHT = "region_right"
        const val EXTRA_REGION_BOTTOM = "region_bottom"
        const val EXTRA_SESSION_ID = "session_id"

        const val MODE_TEXT = "text"
        const val MODE_SCREENSHOT = "screenshot"

        // Broadcast for status updates
        const val BROADCAST_STATUS = "com.screenreader.ocr.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_CAPTURE_COUNT = "capture_count"
        const val EXTRA_SKIP_COUNT = "skip_count"

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            interval: Long = 5000L,
            sessionId: String = "",
            captureMode: String = MODE_TEXT
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_INTERVAL, interval)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CAPTURE_MODE, captureMode)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun createUpdateRegionIntent(
            context: Context,
            left: Int, top: Int, right: Int, bottom: Int
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_UPDATE_REGION
                putExtra(EXTRA_REGION_LEFT, left)
                putExtra(EXTRA_REGION_TOP, top)
                putExtra(EXTRA_REGION_RIGHT, right)
                putExtra(EXTRA_REGION_BOTTOM, bottom)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val ocrProcessor = OcrProcessor()
    private val duplicateDetector = DuplicateDetector()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var captureInterval: Long = 5000L
    private var captureRegion: Rect? = null // null = full screen
    private var isPaused = false
    private var sessionId: String = ""
    private var captureMode: String = MODE_TEXT

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var captureCount = 0
    private var skipCount = 0

    // Screenshot duplicate detection
    private var lastScreenshotHash: Long = 0L

    /**
     * Compute a fast hash from sampled pixels of the bitmap
     */
    private fun computeBitmapHash(bitmap: Bitmap): Long {
        var hash: Long = 0
        val sampleStep = 20 // Sample every 20th pixel for speed
        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                hash = hash * 31 + pixel.toLong()
            }
        }
        return hash
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) {
                captureScreen()
            }
            handler.postDelayed(this, captureInterval)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                captureInterval = intent.getLongExtra(EXTRA_INTERVAL, 5000L)
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: System.currentTimeMillis().toString()
                captureMode = intent.getStringExtra(EXTRA_CAPTURE_MODE) ?: MODE_TEXT

                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, createNotification("準備中..."))
                    startCapture(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_REGION -> {
                captureRegion = Rect(
                    intent.getIntExtra(EXTRA_REGION_LEFT, 0),
                    intent.getIntExtra(EXTRA_REGION_TOP, 0),
                    intent.getIntExtra(EXTRA_REGION_RIGHT, screenWidth),
                    intent.getIntExtra(EXTRA_REGION_BOTTOM, screenHeight)
                )
            }
            ACTION_PAUSE -> {
                isPaused = true
                updateNotification("一時停止中")
            }
            ACTION_RESUME -> {
                isPaused = false
                updateNotification("キャプチャ中...")
            }
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }, handler)

        setupVirtualDisplay()
        handler.postDelayed(captureRunnable, captureInterval)
        updateNotification("キャプチャ中...")
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun captureScreen() {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val fullBitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Always crop to screen width first (remove row padding)
            val cleanBitmap = if (bitmapWidth > screenWidth) {
                Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight).also {
                    fullBitmap.recycle()
                }
            } else {
                fullBitmap
            }

            // Crop to region if specified
            val bitmap = captureRegion?.let { region ->
                val left = region.left.coerceIn(0, screenWidth)
                val top = region.top.coerceIn(0, screenHeight)
                val right = region.right.coerceIn(left, screenWidth)
                val bottom = region.bottom.coerceIn(top, screenHeight)
                val width = right - left
                val height = bottom - top
                if (width > 0 && height > 0) {
                    Bitmap.createBitmap(cleanBitmap, left, top, width, height).also {
                        if (it != cleanBitmap) cleanBitmap.recycle()
                    }
                } else {
                    cleanBitmap
                }
            } ?: cleanBitmap

            // Close image BEFORE processing to free resources
            image.close()

            // Process based on mode
            if (captureMode == MODE_SCREENSHOT) {
                saveScreenshot(bitmap)
            } else {
                processOcr(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            image.close()
        }
    }

    private fun processOcr(bitmap: Bitmap) {
        serviceScope.launch {
            try {
                val result = ocrProcessor.recognizeText(bitmap)

                if (result.text.isBlank()) {
                    Log.d(TAG, "No text found in capture")
                    bitmap.recycle()
                    return@launch
                }

                if (duplicateDetector.isDuplicate(result.text)) {
                    skipCount++
                    Log.d(TAG, "Duplicate text skipped (total skips: $skipCount)")
                    broadcastStatus("重複スキップ")
                    bitmap.recycle()
                    return@launch
                }

                // Save to database
                val entity = OcrTextEntity(
                    text = result.text,
                    wordCount = result.text.length,
                    sessionId = sessionId
                )

                val db = AppDatabase.getInstance(this@ScreenCaptureService)
                db.ocrTextDao().insert(entity)

                captureCount++
                Log.d(TAG, "Text saved (total: $captureCount)")
                broadcastStatus("保存: $captureCount 件 / スキップ: $skipCount 件")
                updateNotification("保存: $captureCount 件 | スキップ: $skipCount 件")

                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing error", e)
                bitmap.recycle()
            }
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Check for duplicate screenshot
                val currentHash = computeBitmapHash(bitmap)
                if (currentHash == lastScreenshotHash && lastScreenshotHash != 0L) {
                    skipCount++
                    Log.d(TAG, "Duplicate screenshot skipped (hash: $currentHash)")
                    broadcastStatus("📸 保存: $captureCount 枚 / スキップ: $skipCount 枚")
                    bitmap.recycle()
                    return@launch
                }
                lastScreenshotHash = currentHash

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                val filename = "ScreenReader_${timestamp}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenReaderOCR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    val outputStream = contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        outputStream.close()

                        if (saved) {
                            // Mark as complete
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, contentValues, null, null)

                            captureCount++
                            Log.d(TAG, "Screenshot saved: $filename (total: $captureCount)")
                            broadcastStatus("📸 保存: $captureCount 枚")
                            updateNotification("📸 スクショ保存: $captureCount 枚")
                        } else {
                            Log.e(TAG, "bitmap.compress returned false")
                            contentResolver.delete(uri, null, null)
                            broadcastStatus("❌ 保存失敗")
                        }
                    } else {
                        Log.e(TAG, "Failed to open output stream")
                        contentResolver.delete(uri, null, null)
                        broadcastStatus("❌ ストリーム作成失敗")
                    }
                } else {
                    Log.e(TAG, "Failed to create MediaStore entry")
                    broadcastStatus("❌ MediaStore作成失敗")
                }

                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot save error: ${e.message}", e)
                broadcastStatus("❌ エラー: ${e.message}")
                bitmap.recycle()
            }
        }
    }

    private fun stopCapture() {
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        ocrProcessor.close()
        serviceScope.cancel()
    }

    private fun createNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = createStopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ScreenReaderApp.CHANNEL_ID_CAPTURE)
            .setContentTitle("ScreenReader OCR")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(statusText: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_CAPTURE_COUNT, captureCount)
            putExtra(EXTRA_SKIP_COUNT, skipCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
