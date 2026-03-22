package com.sysadmindoc.callshield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feature 7: Caller ID overlay.
 * Shows a warning banner when a suspicious (but not blocked) call rings.
 */
class CallerIdOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number") ?: ""
        val confidence = intent?.getIntExtra("confidence", 0) ?: 0
        val reason = intent?.getStringExtra("reason") ?: "suspicious"

        if (number.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(number, confidence, reason)
        return START_NOT_STICKY
    }

    private fun showOverlay(number: String, confidence: Int, reason: String) {
        // Check overlay permission before attempting to show
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E0F38BA8")) // CatRed with alpha
            setPadding(48, 32, 48, 32)

            addView(TextView(context).apply {
                text = "Possible Spam"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = "$number - ${reason.replace("_", " ")} ($confidence% confidence)"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 14f
            })

            setOnClickListener {
                removeOverlay()
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (_: Exception) {
            // Overlay permission not granted
        }

        // Auto-dismiss after 8 seconds
        overlayView?.postDelayed({
            removeOverlay()
            stopSelf()
        }, 8000)
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
