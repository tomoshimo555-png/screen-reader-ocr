package com.screenreader.ocr.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"

        const val ACTION_SHOW = "com.screenreader.ocr.overlay.SHOW"
        const val ACTION_HIDE = "com.screenreader.ocr.overlay.HIDE"
        const val ACTION_UPDATE_STATUS = "com.screenreader.ocr.overlay.UPDATE_STATUS"
        const val EXTRA_STATUS = "status"

        fun createShowIntent(context: Context): Intent {
            return Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
        }

        fun createHideIntent(context: Context): Intent {
            return Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var controlPanel: View? = null
    private var regionFrame: View? = null

    private var statusText: TextView? = null
    private var isPaused = false

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // Region frame position/size (will be calculated from screen size)
    private var regionLeft = 0
    private var regionTop = 0
    private var regionWidth = 0
    private var regionHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Get screen dimensions
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Set initial region to cover nearly full screen
        val margin = (screenWidth * 0.01).toInt()
        regionLeft = margin
        regionTop = (screenHeight * 0.03).toInt() // Minimal space for status bar
        regionWidth = screenWidth - (margin * 2)
        regionHeight = (screenHeight * 0.92).toInt() // Leave minimal space for nav bar
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                showOverlay()
            }
            ACTION_HIDE -> {
                hideOverlay()
                stopSelf()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: ""
                statusText?.text = status
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        // === Capture Region Frame ===
        regionFrame = createRegionFrame()
        val regionParams = WindowManager.LayoutParams(
            regionWidth,
            regionHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = regionLeft
            y = regionTop
        }

        windowManager.addView(regionFrame, regionParams)

        // === Control Panel ===
        controlPanel = createControlPanel()
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 24
        }

        windowManager.addView(controlPanel, controlParams)
        overlayView = controlPanel

        // Send initial region to capture service
        updateCaptureRegion()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createRegionFrame(): FrameLayout {
        val frame = FrameLayout(this)

        // Very thin, subtle border
        val borderView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            background = createBorderDrawable()
        }
        frame.addView(borderView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return frame
    }

    private fun createBorderDrawable(): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable()
        shape.setStroke(2, Color.argb(100, 33, 150, 243))
        shape.setColor(Color.TRANSPARENT)
        shape.cornerRadius = 4f
        return shape
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRegionDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    updateCaptureRegion()
                    true
                }
                else -> false
            }
        }
    }

    private fun createControlPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setPadding(12, 6, 12, 6)
            elevation = 8f
            gravity = Gravity.CENTER_VERTICAL
        }

        // Status text
        statusText = TextView(this).apply {
            text = "待機中"
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(0, 0, 8, 0)
        }
        panel.addView(statusText)

        // Pause/Resume button
        val pauseBtn = createButton("⏸").apply {
            setOnClickListener {
                isPaused = !isPaused
                (it as TextView).text = if (isPaused) "▶" else "⏸"
                val action = if (isPaused) ScreenCaptureService.ACTION_PAUSE
                             else ScreenCaptureService.ACTION_RESUME
                val intent = Intent(this@FloatingOverlayService, ScreenCaptureService::class.java).apply {
                    this.action = action
                }
                startService(intent)
                statusText?.text = if (isPaused) "停止中" else "実行中"
            }
        }
        panel.addView(pauseBtn)

        // Spacer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(8, 1)
        }
        panel.addView(spacer)

        // Stop button
        val stopBtn = createButton("⏹").apply {
            setOnClickListener {
                val intent = ScreenCaptureService.createStopIntent(this@FloatingOverlayService)
                startService(intent)
                hideOverlay()
                stopSelf()
            }
        }
        panel.addView(stopBtn)

        return panel
    }

    private fun createButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(80, 100, 100, 100))
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
        }
    }

    private fun updateCaptureRegion() {
        val frame = regionFrame ?: return
        val params = frame.layoutParams as? WindowManager.LayoutParams ?: return

        val intent = ScreenCaptureService.createUpdateRegionIntent(
            this,
            left = params.x,
            top = params.y,
            right = params.x + params.width,
            bottom = params.y + params.height
        )
        startService(intent)
    }

    private fun hideOverlay() {
        try {
            regionFrame?.let { windowManager.removeView(it) }
            controlPanel?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
        regionFrame = null
        controlPanel = null
        overlayView = null
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
