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
import com.sysadmindoc.callshield.data.OutgoingRiskWarning
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.data.remote.RemoteLookupStatus
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.race
import kotlinx.coroutines.*
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The caller-ID overlay is built with the View API, whose padding/margin/corner
 * setters take physical pixels. The literals below were eyeballed on a ~xxhdpi
 * device (density 3), so treat that as the design baseline: convert each literal
 * to pixels for the *current* density. Result is proportionally identical across
 * mdpi..xxxhdpi (previously oversized on mdpi, shrunken on xxxhdpi) and unchanged
 * on xxhdpi.
 */
private const val OVERLAY_DESIGN_DENSITY = 3f

private fun Context.overlayDp(designPx: Float): Int = overlayDpF(designPx).roundToInt()

private fun Context.overlayDpF(designPx: Float): Float = designPx * resources.displayMetrics.density / OVERLAY_DESIGN_DENSITY

/**
 * Real-time caller ID overlay with live multi-source spam lookup.
 *
 * Shows immediately with area code, then queries SkipCalls, PhoneBlock,
 * and WhoCalledMe in parallel. Updates the overlay in real-time as each
 * source responds. Shows aggregate spam score + Google search button.
 */
@Suppress("DEPRECATION")
class CallerIdOverlayService : Service() {
    companion object {
        internal const val EXTRA_OUTGOING_RISK_WARNING = "outgoing_risk_warning"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_CONFIDENCE = "confidence"
        private const val EXTRA_REASON = "reason"
        private const val FAST_SPAM_HIT_TIMEOUT_MS = 1_500L

        internal fun outgoingRiskIntent(
            context: Context,
            warning: OutgoingRiskWarning,
        ): Intent =
            Intent(context, CallerIdOverlayService::class.java).apply {
                putExtra(EXTRA_NUMBER, warning.number)
                putExtra(EXTRA_CONFIDENCE, warning.confidence)
                putExtra(EXTRA_REASON, warning.reason)
                putExtra(EXTRA_OUTGOING_RISK_WARNING, true)
            }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var lookupJob: Job? = null
    private var dismissRunnable: Runnable? = null

    // Call-state watcher so the overlay dismisses when the call actually ends,
    // instead of lingering for the full 20 s backstop. Held as Any?/nullable so
    // the API 31+ TelephonyCallback type is never referenced on older devices.
    private var callStateCallback: Any? = null
    private var phoneStateListener: android.telephony.PhoneStateListener? = null

    @Volatile private var isOverlayActive = false

    @Volatile private var activeSessionId = 0L

    // What the active session is showing, so a later start for the SAME
    // caller with LESS information can be ignored instead of tearing the
    // session down. Concretely: HeuristicChecker shows a "Possible spam N%"
    // overlay for mid-band scores during checkSpam(), then the screening
    // service's not-spam path fires the generic area-code overlay
    // (confidence 0) for every NANP caller — without this guard the second
    // start destroyed the suspicion warning before the user could read it
    // and restarted the live lookups from scratch.
    @Volatile private var activeNumber: String = ""

    @Volatile private var activeConfidence = 0

    // UI elements we need to update
    private var headerText: TextView? = null
    private var scoreText: TextView? = null
    private var statusText: TextView? = null
    private var callerNameText: TextView? = null
    private var sourcesContainer: LinearLayout? = null
    private var progressBar: ProgressBar? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: ""
        val confidence = intent?.getIntExtra(EXTRA_CONFIDENCE, 0) ?: 0
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: ""
        val outgoingRiskWarning = intent?.getBooleanExtra(EXTRA_OUTGOING_RISK_WARNING, false) ?: false

        if (number.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Keep a higher-information session alive: never let a lower- or
        // equal-confidence restart for the same caller replace an active
        // suspicion overlay (its lookups keep running, its warning stays
        // readable). Outgoing-risk warnings are a different surface and
        // always take over.
        if (!outgoingRiskWarning && isOverlayActive && number == activeNumber &&
            activeConfidence > 0 && confidence <= activeConfidence
        ) {
            return START_NOT_STICKY
        }

        val sessionId =
            showOverlay(number, confidence, reason, outgoingRiskWarning)
                ?: return START_NOT_STICKY
        if (!outgoingRiskWarning) {
            // Incoming caller ID may be enriched live. Outgoing warnings stay
            // local-only and display the exact database decision unchanged.
            runLiveLookups(number, sessionId)
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(
        number: String,
        confidence: Int,
        reason: String,
        outgoingRiskWarning: Boolean,
    ): Long? {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return null
        }

        cancelLookup()
        clearDismissCallback()
        deactivateOverlaySession()
        // Unregister the previous session's call-state watcher before the new
        // registration overwrites the field references. Otherwise every
        // back-to-back overlay leaks one TelephonyRegistry registration until
        // Android's per-process cap makes future registrations fail — which
        // would silently kill idle-dismiss for the rest of the process.
        unregisterCallStateWatcher()
        removeOverlay()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val formatted = PhoneFormatter.formatIsolated(number)
        val digits = filterAsciiDigits(number)
        val sessionId = SystemClock.elapsedRealtimeNanos()

        overlayView =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Rounded bottom corners with premium surface
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#F5080808"))
                        val r = context.overlayDpF(48f)
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) // bottom-left, bottom-right
                    }
                setPadding(context.overlayDp(52f), context.overlayDp(40f), context.overlayDp(52f), context.overlayDp(32f))

                // Subtle accent line at top
                addView(
                    android.view.View(context).apply {
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    context.overlayDp(2f),
                                ).apply { bottomMargin = context.overlayDp(16f) }
                        setBackgroundColor(Color.parseColor("#18A6E3A1"))
                    },
                )

                // Header — updates based on lookup results
                headerText =
                    TextView(context).apply {
                        text =
                            if (outgoingRiskWarning) {
                                context.getString(R.string.overlay_header_outgoing_risk)
                            } else if (confidence > 0) {
                                context.getString(R.string.overlay_header_possible_spam)
                            } else {
                                context.getString(R.string.overlay_header_incoming_call)
                            }
                        setTextColor(if (confidence > 0) Color.parseColor("#FFF38BA8") else Color.parseColor("#FFA6E3A1"))
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.12f
                    }
                addView(headerText)

                // Number + location
                addView(
                    TextView(context).apply {
                        text = formatted
                        setTextColor(Color.parseColor("#FFCDD6F4"))
                        textSize = 24f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = -0.02f
                        setPadding(0, context.overlayDp(8f), 0, context.overlayDp(2f))
                    },
                )
                if (reason.isNotEmpty()) {
                    addView(
                        TextView(context).apply {
                            text = reason
                            setTextColor(Color.parseColor("#FF9399B2"))
                            textSize = 12f
                        },
                    )
                }

                // Score — updates live
                scoreText =
                    TextView(context).apply {
                        text =
                            if (outgoingRiskWarning) {
                                context.getString(R.string.overlay_outgoing_risk_score, confidence)
                            } else if (confidence > 0) {
                                context.getString(R.string.overlay_initial_score, confidence)
                            } else {
                                context.getString(R.string.overlay_score_loading)
                            }
                        setTextColor(Color.parseColor("#FFFAB387"))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, context.overlayDp(10f), 0, 0)
                    }
                addView(scoreText)

                // Loading indicator
                progressBar =
                    ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                        setPadding(0, context.overlayDp(10f), 0, 0)
                        visibility =
                            if (outgoingRiskWarning) {
                                android.view.View.GONE
                            } else {
                                android.view.View.VISIBLE
                            }
                    }
                addView(progressBar)

                // Sources container — results appear here as they come in
                sourcesContainer =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, context.overlayDp(6f), 0, 0)
                    }
                addView(sourcesContainer)

                // Caller name (populated by OpenCNAM lookup)
                callerNameText =
                    TextView(context).apply {
                        text = ""
                        setTextColor(Color.parseColor("#FFB4BEFE"))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        visibility = android.view.View.GONE
                        setPadding(0, context.overlayDp(6f), 0, 0)
                    }
                addView(callerNameText)

                // Status text
                statusText =
                    TextView(context).apply {
                        text =
                            context.getString(
                                if (outgoingRiskWarning) {
                                    R.string.overlay_outgoing_risk_status
                                } else {
                                    R.string.overlay_status_querying
                                },
                            )
                        setTextColor(Color.parseColor("#FF585B70"))
                        textSize = 10f
                        letterSpacing = 0.02f
                        setPadding(0, context.overlayDp(6f), 0, 0)
                    }
                addView(statusText)

                // Buttons — row 1
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, context.overlayDp(16f), 0, 0)

                        // Google search
                        addView(
                            Button(context).apply {
                                text = context.getString(R.string.overlay_action_search)
                                setTextColor(Color.parseColor("#FF89B4FA"))
                                setBackgroundColor(Color.parseColor("#14FFFFFF"))
                                textSize = 11f
                                isAllCaps = false
                                setPadding(context.overlayDp(20f), context.overlayDp(8f), context.overlayDp(20f), context.overlayDp(8f))
                                setOnClickListener {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                    } catch (_: Exception) {
                                    }
                                    dismiss(sessionId)
                                }
                            },
                        )

                        addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                        // Block button
                        addView(
                            Button(context).apply {
                                text = context.getString(R.string.overlay_action_block)
                                setTextColor(Color.parseColor("#FFF38BA8"))
                                setBackgroundColor(Color.parseColor("#14FFFFFF"))
                                textSize = 11f
                                isAllCaps = false
                                setPadding(context.overlayDp(20f), context.overlayDp(8f), context.overlayDp(20f), context.overlayDp(8f))
                                visibility =
                                    if (outgoingRiskWarning) {
                                        android.view.View.GONE
                                    } else {
                                        android.view.View.VISIBLE
                                    }
                                setOnClickListener {
                                    blockFromOverlay(number)
                                    dismiss(sessionId)
                                }
                            },
                        )

                        addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

                        // Dismiss
                        addView(
                            Button(context).apply {
                                text = context.getString(R.string.overlay_action_dismiss)
                                setTextColor(Color.parseColor("#FF585B70"))
                                setBackgroundColor(Color.TRANSPARENT)
                                textSize = 11f
                                isAllCaps = false
                                setPadding(context.overlayDp(20f), context.overlayDp(8f), context.overlayDp(20f), context.overlayDp(8f))
                                setOnClickListener { dismiss(sessionId) }
                            },
                        )
                    },
                )

                // Buttons — row 2: SIT tone
                addView(
                    Button(context).apply {
                        text = context.getString(R.string.overlay_action_sit_tone)
                        setTextColor(Color.parseColor("#FF9399B2"))
                        setBackgroundColor(Color.parseColor("#0AFFFFFF"))
                        textSize = 10f
                        isAllCaps = false
                        setPadding(context.overlayDp(20f), context.overlayDp(6f), context.overlayDp(20f), context.overlayDp(6f))
                        visibility =
                            if (outgoingRiskWarning) {
                                android.view.View.GONE
                            } else {
                                android.view.View.VISIBLE
                            }
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = context.overlayDp(8f) }
                        setOnClickListener { playSitToneFromOverlay() }
                    },
                )
            }

        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP }

        // Publish the session id BEFORE addView so any asynchronous work
        // triggered during view construction (lookup jobs posting back via
        // the handler) can correctly compare against activeSessionId. If
        // addView fails, we roll the session id back in the catch block.
        activeSessionId = sessionId
        activeNumber = number
        activeConfidence = confidence
        try {
            windowManager?.addView(overlayView, params)
            isOverlayActive = true
        } catch (_: Exception) {
            deactivateOverlaySession()
            removeOverlay()
            stopSelf()
            return null
        }

        // Auto-dismiss after 20 seconds (backstop). The call-state watcher below
        // dismisses sooner, right when the call ends, so a 2-3 s blocked/rejected
        // call's overlay doesn't hover over unrelated UI for the full 20 s.
        dismissRunnable = Runnable { dismiss(sessionId) }
        handler.postDelayed(dismissRunnable!!, 20_000)
        registerCallStateWatcher(sessionId, outgoingRiskWarning)
        return sessionId
    }

    /**
     * Dismiss the overlay when the phone returns to [TelephonyManager.CALL_STATE_IDLE].
     * Permission-gated and best-effort: if READ_PHONE_STATE isn't granted or
     * registration fails, the 20 s handler backstop still dismisses the overlay,
     * so current behavior is never regressed.
     */
    private fun registerCallStateWatcher(
        sessionId: Long,
        outgoingRiskWarning: Boolean,
    ) {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return
            // Both registration APIs deliver the *current* state immediately.
            // Incoming overlays register while the phone is RINGING, but an
            // outgoing warning registers during call setup where the radio can
            // still report IDLE — acting on that snapshot would dismiss the
            // warning before the user can read it. Outgoing sessions therefore
            // ignore IDLE until a non-idle state has been observed.
            val shouldDismissOnIdle = idleDismissGate(outgoingRiskWarning)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                registerModernCallStateWatcher(tm, sessionId, shouldDismissOnIdle)
            } else {
                @Suppress("DEPRECATION")
                val listener =
                    object : android.telephony.PhoneStateListener() {
                        @Deprecated("Legacy pre-API-31 call-state listener")
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onCallStateChanged(
                            state: Int,
                            phoneNumber: String?,
                        ) {
                            if (shouldDismissOnIdle(state)) {
                                dismiss(sessionId)
                            }
                        }
                    }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: Exception) {
            // Best-effort — the 20 s handler backstop guarantees dismissal.
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun registerModernCallStateWatcher(
        tm: android.telephony.TelephonyManager,
        sessionId: Long,
        shouldDismissOnIdle: (Int) -> Boolean,
    ) {
        val callback =
            object :
                android.telephony.TelephonyCallback(),
                android.telephony.TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (shouldDismissOnIdle(state)) {
                        dismiss(sessionId)
                    }
                }
            }
        callStateCallback = callback
        tm.registerTelephonyCallback(mainExecutor, callback)
    }

    /**
     * Stateful predicate: for outgoing warnings, IDLE only dismisses after a
     * non-idle state has been seen (the registration snapshot may be IDLE).
     * Incoming overlays keep the original dismiss-on-any-IDLE behavior.
     */
    private fun idleDismissGate(outgoingRiskWarning: Boolean): (Int) -> Boolean {
        var sawNonIdle = !outgoingRiskWarning
        return { state ->
            if (state != android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                sawNonIdle = true
                false
            } else {
                sawNonIdle
            }
        }
    }

    private fun unregisterCallStateWatcher() {
        try {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (callStateCallback as? android.telephony.TelephonyCallback)?.let {
                    tm?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { tm?.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            callStateCallback = null
            phoneStateListener = null
        }
    }

    /**
     * Query all sources in parallel and update the overlay as each responds.
     * Now includes OpenCNAM caller name lookup as a 4th source.
     */
    private fun runLiveLookups(
        number: String,
        sessionId: Long,
    ) {
        data class LookupSnapshot(
            val completed: Int,
            val totalReports: Int,
            val anySpam: Boolean,
        )

        val spamSources = ExternalLookup.spamLookupSources()
        val totalSources = spamSources.size + 1 // spam sources + OpenCNAM
        val stateLock = Any()
        var completed = 0
        var totalReports = 0
        var anySpam = false
        var warmHitShown = false

        fun scoreFor(snapshot: LookupSnapshot): Int =
            when {
                snapshot.totalReports >= 10 -> 95
                snapshot.totalReports >= 5 -> 80
                snapshot.totalReports >= 3 -> 60
                snapshot.anySpam -> 50
                snapshot.totalReports > 0 -> 30
                snapshot.completed >= totalSources -> 0
                else -> -1 // still loading
            }

        fun colorFor(score: Int): String =
            when {
                score >= 70 -> "#FFF38BA8"

                // Red
                score >= 40 -> "#FFFAB387"

                // Orange
                score > 0 -> "#FFF9E2AF"

                // Yellow
                else -> "#FFA6E3A1" // Green
            }

        fun renderScore(snapshot: LookupSnapshot) {
            val score = scoreFor(snapshot)
            if (score >= 0) {
                val color = colorFor(score)
                scoreText?.text =
                    this@CallerIdOverlayService.getString(
                        R.string.overlay_spam_score,
                        score,
                        formatReports(snapshot.totalReports),
                    )
                scoreText?.setTextColor(Color.parseColor(color))
                headerText?.text =
                    when {
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
                sourcesContainer?.addView(
                    TextView(this).apply {
                        val isFallback = result.status.isFallback
                        val icon =
                            when {
                                result.isSpam -> "\u26A0"
                                isFallback -> "!"
                                else -> "\u2713"
                            }
                        val info =
                            when {
                                result.reports > 0 -> formatReports(result.reports)
                                result.isSpam -> this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                                else -> sourceStatusText(result)
                            }
                        text = this@CallerIdOverlayService.getString(R.string.overlay_source_result, icon, result.source, info)
                        setTextColor(
                            Color.parseColor(
                                when {
                                    result.isSpam -> "#FFF38BA8"
                                    isFallback -> "#FFA6ADC8"
                                    else -> "#FFA6E3A1"
                                },
                            ),
                        )
                        textSize = 11f
                        setPadding(0, context.overlayDp(3f), 0, context.overlayDp(3f))
                    },
                )
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
            val shouldPublish =
                synchronized(stateLock) {
                    if (warmHitShown || anySpam) {
                        false
                    } else {
                        warmHitShown = true
                        true
                    }
                }
            if (!shouldPublish) return

            val score =
                scoreFor(
                    LookupSnapshot(
                        completed = totalSources,
                        totalReports = result.reports,
                        anySpam = result.isSpam || result.reports >= 3,
                    ),
                ).coerceAtLeast(50)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                val color = colorFor(score)
                val reportText =
                    if (result.reports > 0) {
                        formatReports(result.reports)
                    } else {
                        this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                    }
                headerText?.text = this@CallerIdOverlayService.getString(R.string.overlay_header_likely_spam)
                headerText?.setTextColor(Color.parseColor(color))
                scoreText?.text = this@CallerIdOverlayService.getString(R.string.overlay_spam_score, score, reportText)
                scoreText?.setTextColor(Color.parseColor(color))
                statusText?.text =
                    this@CallerIdOverlayService.getString(
                        R.string.overlay_status_fast_hit,
                        result.source,
                    )
            }
        }

        lookupJob =
            serviceScope.launch {
                try {
                    coroutineScope {
                        val spamJobs =
                            spamSources.map { source ->
                                async { ExternalLookup.lookupSpamSource(number, source) }
                            }

                        launch {
                            val firstHit: ExternalLookup.SourceResult? =
                                race(
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
                            val callerNameResult = ExternalLookup.lookupCallerNameResult(number)
                            if (callerNameResult.callerName.isNotBlank()) {
                                handler.post {
                                    if (!isCurrentSession(sessionId)) return@post
                                    callerNameText?.text = callerNameResult.callerName
                                    callerNameText?.visibility = android.view.View.VISIBLE
                                }
                            }
                            addSourceResult(callerNameResult.asSourceResult())
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

    private fun sourceStatusText(result: ExternalLookup.SourceResult): String {
        val statusRes =
            when (result.status) {
                RemoteLookupStatus.FOUND -> {
                    if (result.detail.isNotBlank()) {
                        R.string.remote_lookup_status_caller_id_found
                    } else {
                        R.string.remote_lookup_status_found
                    }
                }

                RemoteLookupStatus.CLEAN -> {
                    R.string.remote_lookup_status_clean
                }

                RemoteLookupStatus.DISABLED -> {
                    R.string.remote_lookup_status_disabled
                }

                RemoteLookupStatus.INVALID_INPUT -> {
                    R.string.remote_lookup_status_invalid_input
                }

                RemoteLookupStatus.TIMEOUT -> {
                    R.string.remote_lookup_status_timeout
                }

                RemoteLookupStatus.RATE_LIMITED -> {
                    R.string.remote_lookup_status_rate_limited
                }

                RemoteLookupStatus.HTTP_ERROR -> {
                    R.string.remote_lookup_status_http_error
                }

                RemoteLookupStatus.EMPTY_BODY -> {
                    R.string.remote_lookup_status_empty_body
                }

                RemoteLookupStatus.BODY_TOO_LARGE -> {
                    R.string.remote_lookup_status_body_too_large
                }

                RemoteLookupStatus.UNREADABLE_BODY -> {
                    R.string.remote_lookup_status_unreadable_body
                }

                RemoteLookupStatus.PARSE_ERROR -> {
                    R.string.remote_lookup_status_parse_error
                }

                RemoteLookupStatus.UNAVAILABLE -> {
                    R.string.remote_lookup_status_unavailable
                }
            }
        return getString(statusRes)
    }

    /**
     * Block + community-report the overlay's caller off the main thread.
     * appScope has no CoroutineExceptionHandler: an uncaught SQLiteException
     * (disk full, corruption — a failure class this app explicitly self-heals
     * elsewhere) would kill the whole process mid-call. Same guard pattern as
     * SpamActionReceiver.
     */
    private fun blockFromOverlay(number: String) {
        val description = getString(R.string.desc_blocked_from_overlay)
        val appContext = applicationContext
        CallShieldApp.appScope.launch {
            try {
                val repository =
                    com.sysadmindoc.callshield.data.SpamRepository
                        .getInstance(appContext)
                repository.blockNumber(number, "spam", description)
                com.sysadmindoc.callshield.data.CommunityContributor
                    .contribute(repository.normalizeNumber(number), "spam")
            } catch (e: Exception) {
                android.util.Log.w("CallerIdOverlay", "Overlay block failed", e)
            }
        }
    }

    /**
     * AudioTrack.Builder.build() throws on devices that can't initialize a
     * voice-call track; appScope has no exception handler, so an uncaught
     * throw would kill the process mid-call.
     */
    private fun playSitToneFromOverlay() {
        if (SitTonePlayer.isPlaying()) return
        val appContext = applicationContext
        CallShieldApp.appScope.launch {
            try {
                SitTonePlayer.play(appContext)
            } catch (e: Exception) {
                android.util.Log.w("CallerIdOverlay", "SIT tone failed", e)
            }
        }
    }

    private fun dismiss(expectedSessionId: Long? = null) {
        if (expectedSessionId != null && activeSessionId != expectedSessionId) {
            return
        }
        cancelLookup()
        clearDismissCallback()
        unregisterCallStateWatcher()
        handler.removeCallbacksAndMessages(null)
        deactivateOverlaySession()
        removeOverlay()
        stopSelf()
    }

    private fun isCurrentSession(sessionId: Long): Boolean = isOverlayActive && activeSessionId == sessionId && overlayView != null

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
        activeNumber = ""
        activeConfidence = 0
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        headerText = null
        scoreText = null
        statusText = null
        callerNameText = null
        sourcesContainer = null
        progressBar = null
        windowManager = null
    }

    override fun onDestroy() {
        cancelLookup()
        clearDismissCallback()
        unregisterCallStateWatcher()
        deactivateOverlaySession()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }
}
