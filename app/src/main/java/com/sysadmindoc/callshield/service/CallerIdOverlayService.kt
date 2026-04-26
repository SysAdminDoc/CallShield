package com.sysadmindoc.callshield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.util.race
import kotlinx.coroutines.*
import java.text.NumberFormat

/**
 * Real-time caller ID overlay with live multi-source spam lookup.
 *
 * Shows immediately with area code, then queries SkipCalls, PhoneBlock,
 * and WhoCalledMe in parallel. Updates the overlay in real-time as each
 * source responds. Shows aggregate spam score + Google search button.
 */
class CallerIdOverlayService : Service() {

    companion object {
        // Sentinel for "no data" — used when we haven't collected a STIR/SHAKEN
        // verdict yet (e.g. heuristic-triggered overlays on pre-Android-11 devices,
        // or call-screening paths that don't expose verification status).
        const val VERIFICATION_STATUS_UNKNOWN = -1
        private const val FAST_SPAM_HIT_TIMEOUT_MS = 1_500L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var lookupJob: Job? = null
    private var dismissRunnable: Runnable? = null

    @Volatile private var isOverlayActive = false
    @Volatile private var activeSessionId = 0L

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
        val verificationStatus = intent?.getIntExtra("verification_status", VERIFICATION_STATUS_UNKNOWN)
            ?: VERIFICATION_STATUS_UNKNOWN

        if (number.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        val sessionId = showOverlay(number, confidence, reason, verificationStatus)
            ?: return START_NOT_STICKY
        // Launch parallel lookups
        runLiveLookups(number, sessionId)
        return START_NOT_STICKY
    }

    private fun showOverlay(number: String, confidence: Int, reason: String, verificationStatus: Int): Long? {
        if (!android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return null }

        cancelLookup()
        clearDismissCallback()
        deactivateOverlaySession()
        removeOverlay()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val formatted = PhoneFormatter.format(number)
        val digits = number.filter { it.isDigit() }
        val sessionId = SystemClock.elapsedRealtimeNanos()

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Rounded bottom corners with premium surface
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5080808"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 48f, 48f, 48f, 48f) // bottom-left, bottom-right
            }
            setPadding(52, 40, 52, 32)

            // Subtle accent line at top
            addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2
                ).apply { bottomMargin = 16 }
                setBackgroundColor(Color.parseColor("#18A6E3A1"))
            })

            // Header — updates based on lookup results
            headerText = TextView(context).apply {
                text = if (confidence > 0) {
                    context.getString(R.string.overlay_header_possible_spam)
                } else {
                    context.getString(R.string.overlay_header_incoming_call)
                }
                setTextColor(if (confidence > 0) Color.parseColor("#FFF38BA8") else Color.parseColor("#FFA6E3A1"))
                textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
            }
            addView(headerText)

            // Number + location
            addView(TextView(context).apply {
                text = formatted
                setTextColor(Color.parseColor("#FFCDD6F4")); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
                letterSpacing = -0.02f
                setPadding(0, 8, 0, 2)
            })
            if (reason.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = reason; setTextColor(Color.parseColor("#FF9399B2")); textSize = 12f
                })
            }

            // Score — updates live
            scoreText = TextView(context).apply {
                text = if (confidence > 0) {
                    context.getString(R.string.overlay_initial_score, confidence)
                } else {
                    context.getString(R.string.overlay_score_loading)
                }
                setTextColor(Color.parseColor("#FFFAB387")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 10, 0, 0)
            }
            addView(scoreText)

            // Loading indicator
            progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                setPadding(0, 10, 0, 0)
            }
            addView(progressBar)

            // Sources container — results appear here as they come in
            sourcesContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 0)
            }
            addView(sourcesContainer)

            // Caller name (populated by OpenCNAM lookup)
            callerNameText = TextView(context).apply {
                text = ""
                setTextColor(Color.parseColor("#FFB4BEFE"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                visibility = android.view.View.GONE
                setPadding(0, 6, 0, 0)
            }
            addView(callerNameText)

            // Status text
            statusText = TextView(context).apply {
                text = context.getString(R.string.overlay_status_querying)
                setTextColor(Color.parseColor("#FF585B70")); textSize = 10f
                letterSpacing = 0.02f
                setPadding(0, 6, 0, 0)
            }
            addView(statusText)

            // Buttons — row 1
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)

                // Google search
                addView(Button(context).apply {
                    text = context.getString(R.string.overlay_action_search)
                    setTextColor(Color.parseColor("#FF89B4FA"))
                    setBackgroundColor(Color.parseColor("#14FFFFFF"))
                    textSize = 11f; isAllCaps = false; setPadding(20, 8, 20, 8)
                    setOnClickListener {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
                        dismiss(sessionId)
                    }
                })

                addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                // Block button
                addView(Button(context).apply {
                    text = context.getString(R.string.overlay_action_block)
                    setTextColor(Color.parseColor("#FFF38BA8"))
                    setBackgroundColor(Color.parseColor("#14FFFFFF"))
                    textSize = 11f; isAllCaps = false; setPadding(20, 8, 20, 8)
                    setOnClickListener {
                        CallShieldApp.appScope.launch {
                            com.sysadmindoc.callshield.data.SpamRepository.getInstance(context).blockNumber(number, "spam", "Blocked from overlay")
                            com.sysadmindoc.callshield.data.CommunityContributor.contribute(number, "spam")
                        }
                        dismiss(sessionId)
                    }
                })

                addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                // Dismiss
                addView(Button(context).apply {
                    text = context.getString(R.string.overlay_action_dismiss)
                    setTextColor(Color.parseColor("#FF585B70"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 11f; isAllCaps = false; setPadding(20, 8, 20, 8)
                    setOnClickListener { dismiss(sessionId) }
                })
            })

            // Buttons — row 2: SIT tone
            addView(Button(context).apply {
                text = context.getString(R.string.overlay_action_sit_tone)
                setTextColor(Color.parseColor("#FF9399B2"))
                setBackgroundColor(Color.parseColor("#0AFFFFFF"))
                textSize = 10f; isAllCaps = false; setPadding(20, 6, 20, 6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setOnClickListener {
                    if (!SitTonePlayer.isPlaying()) {
                        CallShieldApp.appScope.launch {
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

        // Publish the session id BEFORE addView so any asynchronous work
        // triggered during view construction (lookup jobs posting back via
        // the handler) can correctly compare against activeSessionId. If
        // addView fails, we roll the session id back in the catch block.
        activeSessionId = sessionId
        try {
            windowManager?.addView(overlayView, params)
            isOverlayActive = true
        } catch (_: Exception) {
            deactivateOverlaySession()
            removeOverlay()
            stopSelf()
            return null
        }

        // Auto-dismiss after 20 seconds (longer now since we're showing lookup results)
        dismissRunnable = Runnable { dismiss(sessionId) }
        handler.postDelayed(dismissRunnable!!, 20_000)
        return sessionId
    }

    /**
     * Query all sources in parallel and update the overlay as each responds.
     * Now includes OpenCNAM caller name lookup as a 4th source.
     */
    private fun runLiveLookups(number: String, sessionId: Long) {
        data class LookupSnapshot(
            val completed: Int,
            val totalReports: Int,
            val anySpam: Boolean,
        )

        val spamSources = ExternalLookup.spamLookupSources()
        val totalSources = spamSources.size + 1  // spam sources + OpenCNAM
        val stateLock = Any()
        var completed = 0
        var totalReports = 0
        var anySpam = false
        var warmHitShown = false

        fun scoreFor(snapshot: LookupSnapshot): Int = when {
            snapshot.totalReports >= 10 -> 95
            snapshot.totalReports >= 5 -> 80
            snapshot.totalReports >= 3 -> 60
            snapshot.anySpam -> 50
            snapshot.totalReports > 0 -> 30
            snapshot.completed >= totalSources -> 0
            else -> -1 // still loading
        }

        fun colorFor(score: Int): String = when {
            score >= 70 -> "#FFF38BA8" // Red
            score >= 40 -> "#FFFAB387" // Orange
            score > 0 -> "#FFF9E2AF"   // Yellow
            else -> "#FFA6E3A1"         // Green
        }

        fun renderScore(snapshot: LookupSnapshot) {
            val score = scoreFor(snapshot)
            if (score >= 0) {
                val color = colorFor(score)
                scoreText?.text = this@CallerIdOverlayService.getString(
                    R.string.overlay_spam_score,
                    score,
                    formatReports(snapshot.totalReports)
                )
                scoreText?.setTextColor(Color.parseColor(color))
                headerText?.text = when {
                    score >= 50 -> this@CallerIdOverlayService.getString(R.string.overlay_header_likely_spam)
                    score > 0 -> this@CallerIdOverlayService.getString(R.string.overlay_header_suspicious)
                    else -> this@CallerIdOverlayService.getString(R.string.overlay_header_safe)
                }
                headerText?.setTextColor(Color.parseColor(color))
            }
            if (snapshot.completed >= totalSources) {
                progressBar?.visibility = android.view.View.GONE
                statusText?.text = this@CallerIdOverlayService.getString(R.string.overlay_status_complete)
            }
        }

        fun recordCompletion(result: ExternalLookup.SourceResult?): LookupSnapshot =
            synchronized(stateLock) {
                completed++
                if (result != null) {
                    totalReports += result.reports
                    if (result.isSpam) anySpam = true
                }
                LookupSnapshot(completed, totalReports, anySpam)
            }

        fun addSourceResult(result: ExternalLookup.SourceResult) {
            val snapshot = recordCompletion(result)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                sourcesContainer?.addView(TextView(this).apply {
                    val icon = if (result.isSpam) "\u26A0" else "\u2713"
                    val info = if (result.reports > 0) {
                        formatReports(result.reports)
                    } else if (result.isSpam) {
                        this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                    } else {
                        this@CallerIdOverlayService.getString(R.string.overlay_source_clean)
                    }
                    text = this@CallerIdOverlayService.getString(R.string.overlay_source_result, icon, result.source, info)
                    setTextColor(Color.parseColor(if (result.isSpam) "#FFF38BA8" else "#FFA6E3A1"))
                    textSize = 11f
                    setPadding(0, 3, 0, 3)
                })
                renderScore(snapshot)
            }
        }

        fun markSourceFinished() {
            val snapshot = recordCompletion(null)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                renderScore(snapshot)
            }
        }

        fun publishWarmHit(result: ExternalLookup.SourceResult) {
            val shouldPublish = synchronized(stateLock) {
                if (warmHitShown || anySpam) {
                    false
                } else {
                    warmHitShown = true
                    true
                }
            }
            if (!shouldPublish) return

            val score = scoreFor(
                LookupSnapshot(
                    completed = totalSources,
                    totalReports = result.reports,
                    anySpam = result.isSpam || result.reports >= 3,
                )
            ).coerceAtLeast(50)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                val color = colorFor(score)
                val reportText = if (result.reports > 0) {
                    formatReports(result.reports)
                } else {
                    this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                }
                headerText?.text = this@CallerIdOverlayService.getString(R.string.overlay_header_likely_spam)
                headerText?.setTextColor(Color.parseColor(color))
                scoreText?.text = this@CallerIdOverlayService.getString(R.string.overlay_spam_score, score, reportText)
                scoreText?.setTextColor(Color.parseColor(color))
                statusText?.text = this@CallerIdOverlayService.getString(
                    R.string.overlay_status_fast_hit,
                    result.source
                )
            }
        }

        lookupJob = serviceScope.launch {
            try {
                coroutineScope {
                    val spamJobs = spamSources.map { source ->
                        async { ExternalLookup.lookupSpamSource(number, source) }
                    }

                    launch {
                        val firstHit: ExternalLookup.SourceResult? = race(
                            competitors = spamJobs,
                            timeoutMillis = FAST_SPAM_HIT_TIMEOUT_MS,
                            decisive = { result ->
                                result != null && (result.isSpam || result.reports >= 3)
                            },
                            onTimeout = null,
                        ) { job ->
                            job.await()
                        }
                        if (firstHit != null) {
                            publishWarmHit(firstHit)
                        }
                    }

                    spamJobs.forEach { job ->
                        launch {
                            val result = job.await()
                            if (result != null) {
                                addSourceResult(result)
                            } else {
                                markSourceFinished()
                            }
                        }
                    }

                    launch {
                        val callerName = ExternalLookup.lookupCallerName(number)
                        if (callerName.isNotBlank()) {
                            handler.post {
                                if (!isCurrentSession(sessionId)) return@post
                                callerNameText?.text = callerName
                                callerNameText?.visibility = android.view.View.VISIBLE
                            }
                        }
                        markSourceFinished()
                    }
                }
            } catch (_: CancellationException) {
                // A newer overlay session replaced this one.
            } catch (_: Exception) {
                handler.post {
                    if (!isCurrentSession(sessionId)) return@post
                    statusText?.text = this@CallerIdOverlayService.getString(R.string.overlay_status_failed)
                    progressBar?.visibility = android.view.View.GONE
                }
            } finally {
                if (activeSessionId == sessionId) {
                    lookupJob = null
                }
            }
        }
    }

    private fun formatReports(reports: Int): String {
        val localizedCount = NumberFormat.getIntegerInstance().format(reports)
        return resources.getQuantityString(R.plurals.overlay_reports_count, reports, localizedCount)
    }

    private fun dismiss(expectedSessionId: Long? = null) {
        if (expectedSessionId != null && activeSessionId != expectedSessionId) {
            return
        }
        cancelLookup()
        clearDismissCallback()
        handler.removeCallbacksAndMessages(null)
        deactivateOverlaySession()
        removeOverlay()
        stopSelf()
    }

    private fun isCurrentSession(sessionId: Long): Boolean =
        isOverlayActive && activeSessionId == sessionId && overlayView != null

    private fun cancelLookup() {
        lookupJob?.cancel()
        lookupJob = null
    }

    private fun clearDismissCallback() {
        dismissRunnable?.let(handler::removeCallbacks)
        dismissRunnable = null
    }

    private fun deactivateOverlaySession() {
        activeSessionId = 0L
        isOverlayActive = false
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null; headerText = null; scoreText = null; statusText = null
        callerNameText = null; sourcesContainer = null; progressBar = null
        windowManager = null
    }

    override fun onDestroy() {
        cancelLookup()
        clearDismissCallback()
        deactivateOverlaySession()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }
}
