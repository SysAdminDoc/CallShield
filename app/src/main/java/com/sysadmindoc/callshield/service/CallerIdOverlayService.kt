package com.sysadmindoc.callshield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sysadmindoc.callshield.data.PhoneFormatter

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val formatted = PhoneFormatter.format(number)

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0181818"))
            setPadding(48, 36, 48, 24)

            // Warning header with red accent
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = "POSSIBLE SPAM"
                    setTextColor(Color.parseColor("#FFF38BA8"))
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 16, 0)
                })
                addView(TextView(context).apply {
                    text = "${confidence}%"
                    setTextColor(Color.parseColor("#FFFAB387"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            // Number
            addView(TextView(context).apply {
                text = formatted
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 8, 0, 4)
            })

            // Reason
            addView(TextView(context).apply {
                text = reason.replace("_", " ").replaceFirstChar { it.uppercase() }
                setTextColor(Color.parseColor("#AAFFFFFF"))
                textSize = 13f
            })

            // Button row
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)

                // Google search button
                addView(Button(context).apply {
                    text = "Search Google"
                    setTextColor(Color.parseColor("#FF89B4FA"))
                    setBackgroundColor(Color.parseColor("#20FFFFFF"))
                    textSize = 12f
                    isAllCaps = false
                    setPadding(24, 8, 24, 8)
                    setOnClickListener {
                        val searchUrl = "https://www.google.com/search?q=${Uri.encode("$number phone number spam")}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        removeOverlay()
                        stopSelf()
                    }
                })

                // Spacer
                addView(android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                // Dismiss button
                addView(Button(context).apply {
                    text = "Dismiss"
                    setTextColor(Color.parseColor("#FF6C7086"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 12f
                    isAllCaps = false
                    setPadding(24, 8, 24, 8)
                    setOnClickListener {
                        removeOverlay()
                        stopSelf()
                    }
                })
            })
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
        } catch (_: Exception) {}

        // Auto-dismiss after 12 seconds
        overlayView?.postDelayed({
            removeOverlay()
            stopSelf()
        }, 12000)
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
