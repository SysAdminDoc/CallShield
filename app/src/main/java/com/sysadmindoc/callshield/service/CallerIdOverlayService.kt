package com.sysadmindoc.callshield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import kotlinx.coroutines.*

/**
 * Real-time caller ID overlay with live multi-source spam lookup.
 *
 * Shows immediately with area code, then queries SkipCalls, PhoneBlock,
 * and WhoCalledMe in parallel. Updates the overlay in real-time as each
 * source responds. Shows aggregate spam score + Google search button.
 */
class CallerIdOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // UI elements we need to update
    private var headerText: TextView? = null
    private var scoreText: TextView? = null
    private var statusText: TextView? = null
    private var callerNameText: TextView? = null
    private var sourcesContainer: LinearLayout? = null
    private var progressBar: ProgressBar? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number") ?: ""
        val confidence = intent?.getIntExtra("confidence", 0) ?: 0
        val reason = intent?.getStringExtra("reason") ?: ""

        if (number.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        showOverlay(number, confidence, reason)
        // Launch parallel lookups
        runLiveLookups(number)
        return START_NOT_STICKY
    }

    private fun showOverlay(number: String, confidence: Int, reason: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return }

        removeOverlay()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val formatted = PhoneFormatter.format(number)
        val digits = number.filter { it.isDigit() }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0101010"))
            setPadding(48, 36, 48, 24)

            // Header — updates based on lookup results
            headerText = TextView(context).apply {
                text = if (confidence > 0) "POSSIBLE SPAM" else "INCOMING CALL"
                setTextColor(if (confidence > 0) Color.parseColor("#FFF38BA8") else Color.parseColor("#FFA6E3A1"))
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            }
            addView(headerText)

            // Number + location
            addView(TextView(context).apply {
                text = formatted
                setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 6, 0, 2)
            })
            if (reason.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = reason; setTextColor(Color.parseColor("#AAFFFFFF")); textSize = 12f
                })
            }

            // Score — updates live
            scoreText = TextView(context).apply {
                text = if (confidence > 0) "Score: $confidence%" else "Checking databases..."
                setTextColor(Color.parseColor("#FFFAB387")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 8, 0, 0)
            }
            addView(scoreText)

            // Loading indicator
            progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                setPadding(0, 8, 0, 0)
            }
            addView(progressBar)

            // Sources container — results appear here as they come in
            sourcesContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 4, 0, 0)
            }
            addView(sourcesContainer)

            // Caller name (populated by OpenCNAM lookup)
            callerNameText = TextView(context).apply {
                text = ""
                setTextColor(Color.parseColor("#FFB4BEFE"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                visibility = android.view.View.GONE
                setPadding(0, 4, 0, 0)
            }
            addView(callerNameText)

            // Status text
            statusText = TextView(context).apply {
                text = "Querying SkipCalls, PhoneBlock, WhoCalledMe, OpenCNAM..."
                setTextColor(Color.parseColor("#666666")); textSize = 10f
                setPadding(0, 4, 0, 0)
            }
            addView(statusText)

            // Buttons — row 1
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 0)

                // Google search
                addView(Button(context).apply {
                    text = "Search"
                    setTextColor(Color.parseColor("#FF89B4FA"))
                    setBackgroundColor(Color.parseColor("#20FFFFFF"))
                    textSize = 11f; isAllCaps = false; setPadding(16, 6, 16, 6)
                    setOnClickListener {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
                        dismiss()
                    }
                })

                addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                // Block button
                addView(Button(context).apply {
                    text = "Block"
                    setTextColor(Color.parseColor("#FFF38BA8"))
                    setBackgroundColor(Color.parseColor("#20FFFFFF"))
                    textSize = 11f; isAllCaps = false; setPadding(16, 6, 16, 6)
                    setOnClickListener {
                        CoroutineScope(Dispatchers.IO).launch {
                            com.sysadmindoc.callshield.data.SpamRepository.getInstance(context).blockNumber(number, "spam", "Blocked from overlay")
                            com.sysadmindoc.callshield.data.CommunityContributor.contribute(number, "spam")
                        }
                        dismiss()
                    }
                })

                addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                // Dismiss
                addView(Button(context).apply {
                    text = "Dismiss"
                    setTextColor(Color.parseColor("#FF6C7086"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 11f; isAllCaps = false; setPadding(16, 6, 16, 6)
                    setOnClickListener { dismiss() }
                })
            })

            // Buttons — row 2: SIT tone
            addView(Button(context).apply {
                text = "\uD83D\uDD08 Play SIT Tone (anti-autodialer)"
                setTextColor(Color.parseColor("#FFA6ADC8"))
                setBackgroundColor(Color.parseColor("#15FFFFFF"))
                textSize = 10f; isAllCaps = false; setPadding(16, 4, 16, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener {
                    if (!SitTonePlayer.isPlaying()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            SitTonePlayer.play(context)
                        }
                    }
                }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        try { windowManager?.addView(overlayView, params) } catch (_: Exception) {}

        // Auto-dismiss after 20 seconds (longer now since we're showing lookup results)
        handler.postDelayed({ dismiss() }, 20000)
    }

    /**
     * Query all sources in parallel and update the overlay as each responds.
     * Now includes OpenCNAM caller name lookup as a 4th source.
     */
    private fun runLiveLookups(number: String) {
        var completed = 0
        var totalReports = 0
        var anySpam = false
        val totalSources = 4  // SkipCalls + PhoneBlock + WhoCalledMe + OpenCNAM

        fun updateScore() {
            handler.post {
                if (overlayView == null) return@post
                val score = when {
                    totalReports >= 10 -> 95
                    totalReports >= 5 -> 80
                    totalReports >= 3 -> 60
                    anySpam -> 50
                    totalReports > 0 -> 30
                    completed >= totalSources -> 0
                    else -> -1 // still loading
                }
                if (score >= 0) {
                    val color = when {
                        score >= 70 -> "#FFF38BA8" // Red
                        score >= 40 -> "#FFFAB387" // Orange
                        score > 0 -> "#FFF9E2AF"   // Yellow
                        else -> "#FFA6E3A1"         // Green
                    }
                    scoreText?.text = "Spam Score: $score% ($totalReports reports)"
                    scoreText?.setTextColor(Color.parseColor(color))
                    headerText?.text = if (score >= 50) "LIKELY SPAM" else if (score > 0) "SUSPICIOUS" else "LOOKS SAFE"
                    headerText?.setTextColor(Color.parseColor(if (score >= 50) "#FFF38BA8" else if (score > 0) "#FFF9E2AF" else "#FFA6E3A1"))
                }
                if (completed >= totalSources) {
                    progressBar?.visibility = android.view.View.GONE
                    statusText?.text = "All sources checked"
                }
            }
        }

        fun addSourceResult(name: String, isSpam: Boolean, reports: Int, detail: String) {
            completed++
            totalReports += reports
            if (isSpam) anySpam = true
            handler.post {
                sourcesContainer?.addView(TextView(this).apply {
                    val icon = if (isSpam) "\u26A0" else "\u2713"
                    val info = if (reports > 0) "$reports reports" else if (isSpam) "Flagged" else "Clean"
                    text = "$icon $name: $info"
                    setTextColor(Color.parseColor(if (isSpam) "#FFF38BA8" else "#FFA6E3A1"))
                    textSize = 11f
                    setPadding(0, 2, 0, 2)
                })
            }
            updateScore()
        }

        // Fire all lookups in parallel
        scope.launch {
            try {
                val result = ExternalLookup.lookupAll(number)
                for (src in result.sources) {
                    addSourceResult(src.source, src.isSpam, src.reports, src.detail)
                }

                // Show caller name from OpenCNAM if available
                if (result.callerName.isNotBlank()) {
                    handler.post {
                        callerNameText?.text = result.callerName
                        callerNameText?.visibility = android.view.View.VISIBLE
                    }
                }
                // Count OpenCNAM as completed (it's bundled in lookupAll)
                completed++
                updateScore()

                // Fill remaining if some spam sources returned null
                while (completed < totalSources) {
                    completed++
                    updateScore()
                }
            } catch (_: Exception) {
                handler.post {
                    statusText?.text = "Lookup failed"
                    progressBar?.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        scope.cancel()
        stopSelf()
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null; headerText = null; scoreText = null; statusText = null
        callerNameText = null; sourcesContainer = null; progressBar = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }
}
