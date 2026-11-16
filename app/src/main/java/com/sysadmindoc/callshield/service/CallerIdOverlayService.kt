package com.sysadmindoc.callshield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.toArgb
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CallerCountry
import com.sysadmindoc.callshield.data.OfficialLines
import com.sysadmindoc.callshield.data.OutgoingRiskWarning
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.data.remote.RemoteLookupStatus
import com.sysadmindoc.callshield.data.repository.BlocklistRepository
import com.sysadmindoc.callshield.ui.spamTypeLabelRes
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import com.sysadmindoc.callshield.ui.theme.paletteFor
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

/** The overlay card's bottom corners, in dp, on the app's 0/4/6/8/10/12dp scale. */
internal const val OVERLAY_CORNER_DP = 12f

/** The smallest text the overlay draws, in sp; only the number itself is larger. */
internal const val OVERLAY_TEXT_SP = 14f

/** Every overlay button's minimum touch target, in dp. */
internal const val OVERLAY_TOUCH_TARGET_DP = 48f

/** The web search the overlay's Search button opens, in the phone's language. */
internal fun overlaySearchUrl(
    context: Context,
    digits: String,
): String = "https://www.google.com/search?q=" + Uri.encode(context.getString(R.string.overlay_search_query, digits))

/**
 * The overlay's verdict line. A warning that says why is easier to act on than
 * a bare score, so a flagged call names the reason that decided it.
 */
internal fun overlayVerdictLine(
    context: Context,
    confidence: Int,
    reason: String,
): String =
    if (reason.isNotBlank()) {
        context.getString(R.string.overlay_score_with_reason, confidence, reason)
    } else {
        context.getString(R.string.overlay_initial_score, confidence)
    }

private fun Context.overlayDp(designPx: Float): Int = overlayDpF(designPx).roundToInt()

/** [OVERLAY_TOUCH_TARGET_DP] in real dp, not the overlay's design pixels. */
private fun Context.touchTargetPx(): Int = (OVERLAY_TOUCH_TARGET_DP * resources.displayMetrics.density).roundToInt()

private fun Context.overlayDpF(designPx: Float): Float = designPx * resources.displayMetrics.density / OVERLAY_DESIGN_DENSITY

internal fun shouldRunLiveCallerEnrichment(
    confidence: Int,
    optedIn: Boolean,
): Boolean = optedIn && confidence > 0

/**
 * The overlay's live-lookup score, or -1 to leave the local verdict showing:
 * while sources are still answering, and when every source failed. A failed
 * lookup is no evidence the caller is safe, and scoring it 0 turned a locally
 * flagged call into a green "Looks Safe".
 */
@Suppress("MagicNumber")
internal fun liveLookupScore(
    totalReports: Int,
    anySpam: Boolean,
    allFinished: Boolean,
    definitive: Int,
): Int =
    when {
        totalReports >= 10 -> 95
        totalReports >= 5 -> 80
        totalReports >= 3 -> 60
        anySpam -> 50
        totalReports > 0 -> 30
        allFinished && definitive > 0 -> 0
        else -> -1
    }

/** The overlay's header before any live lookup answers. */
internal fun overlayInitialHeaderRes(
    outgoingRiskWarning: Boolean,
    confidence: Int,
): Int =
    when {
        outgoingRiskWarning -> R.string.overlay_header_outgoing_risk
        confidence > 0 -> R.string.overlay_header_possible_spam
        else -> R.string.overlay_header_incoming_call
    }

/** The status line before any live lookup answers. */
internal fun overlayInitialStatusRes(
    outgoingRiskWarning: Boolean,
    liveEnrichmentEnabled: Boolean,
): Int =
    when {
        outgoingRiskWarning -> R.string.overlay_outgoing_risk_status
        liveEnrichmentEnabled -> R.string.overlay_status_querying
        else -> R.string.overlay_status_local_only
    }

/** The header once the live lookups give the call a [score]. */
internal fun overlayScoredHeaderRes(score: Int): Int =
    when {
        score >= 50 -> R.string.overlay_header_likely_spam
        score > 0 -> R.string.overlay_header_suspicious
        else -> R.string.overlay_header_safe
    }

/** The status line once every live lookup source has finished. */
internal fun overlayFinishedStatusRes(snapshot: LiveLookupSnapshot): Int =
    when {
        snapshot.uncategorized && !snapshot.anySpam && snapshot.totalReports == 0 -> R.string.remote_lookup_status_uncategorized
        snapshot.definitive > 0 -> R.string.overlay_status_complete
        else -> R.string.detail_no_definitive_source_result
    }

/** The score's color: red from 70, orange from 40, yellow above 0, green at 0. */
internal fun overlayScoreColor(
    score: Int,
    palette: CallerIdOverlayPalette,
): Int =
    when {
        score >= 70 -> palette.error
        score >= 40 -> palette.peach
        score > 0 -> palette.warning
        else -> palette.primary
    }

/** The mark in front of one source's row in the overlay. */
internal fun overlaySourceIcon(result: ExternalLookup.SourceResult): String =
    when {
        result.isSpam -> "\u26A0"
        result.status == RemoteLookupStatus.UNCATEGORIZED -> "!"
        result.status.isFallback -> "!"
        else -> "\u2713"
    }

/** The color of one source's row: red for spam, muted for an answer that isn't one. */
internal fun overlaySourceColor(
    result: ExternalLookup.SourceResult,
    palette: CallerIdOverlayPalette,
): Int =
    when {
        result.isSpam -> palette.error
        result.status == RemoteLookupStatus.UNCATEGORIZED -> palette.overlay
        result.status.isFallback -> palette.overlay
        else -> palette.primary
    }

/** What the live lookups have said so far. */
internal data class LiveLookupSnapshot(
    val completed: Int,
    val totalReports: Int,
    val anySpam: Boolean,
    /** Sources that answered FOUND or CLEAN, as opposed to failing. */
    val definitive: Int,
    val uncategorized: Boolean = false,
)

/**
 * The live lookups' running count. Sources answer on different threads, so
 * every read and change goes through this object's lock.
 */
internal class LiveLookupTally {
    private var completed = 0
    private var totalReports = 0
    private var anySpam = false
    private var definitive = 0
    private var uncategorized = false
    private var warmHitShown = false

    /** Counts one finished source; [result] is null when it gave no answer. */
    @Synchronized
    fun record(result: ExternalLookup.SourceResult?): LiveLookupSnapshot {
        completed++
        if (result != null) {
            totalReports += result.reports
            if (result.isSpam) anySpam = true
            if (result.status == RemoteLookupStatus.FOUND || result.status == RemoteLookupStatus.CLEAN) definitive++
            if (result.status == RemoteLookupStatus.UNCATEGORIZED) uncategorized = true
        }
        return LiveLookupSnapshot(completed, totalReports, anySpam, definitive, uncategorized)
    }

    /** True once, for the first fast hit, and never after a source has said spam. */
    @Synchronized
    fun claimWarmHit(): Boolean {
        if (warmHitShown || anySpam) return false
        warmHitShown = true
        return true
    }
}

private fun goneIf(hidden: Boolean): Int = if (hidden) android.view.View.GONE else android.view.View.VISIBLE

/** One of the overlay's buttons, with the full touch target. */
private fun Context.overlayButton(
    label: Int,
    textColor: Int,
    backgroundColor: Int,
    verticalPadding: Float,
    minTouchWidth: Boolean = true,
): Button =
    Button(this).apply {
        text = context.getString(label)
        setTextColor(textColor)
        setBackgroundColor(backgroundColor)
        textSize = OVERLAY_TEXT_SP
        isAllCaps = false
        minHeight = context.touchTargetPx()
        minimumHeight = context.touchTargetPx()
        if (minTouchWidth) {
            minWidth = context.touchTargetPx()
            minimumWidth = context.touchTargetPx()
        }
        setPadding(context.overlayDp(20f), context.overlayDp(verticalPadding), context.overlayDp(20f), context.overlayDp(verticalPadding))
    }

internal fun overlayReasonLabelRes(reason: String): Int? {
    val normalized = reason.trim().lowercase().replace(' ', '_')
    return when (normalized) {
        "premium_rate" -> R.string.overlay_reason_premium_rate
        "wangiri_country" -> R.string.overlay_reason_wangiri
        "invalid_format" -> R.string.overlay_reason_invalid_format
        "voip_spam_range", "high_spam_npa" -> R.string.overlay_reason_voip_range
        "hot_campaign_range" -> R.string.overlay_reason_hot_campaign
        "community_watch" -> R.string.overlay_reason_community_watch
        "toll_free" -> R.string.overlay_reason_toll_free
        "neighbor_spoof" -> R.string.overlay_reason_neighbor_spoof
        "rapid_fire" -> R.string.overlay_reason_rapid_fire
        "spam_keywords" -> R.string.overlay_reason_spam_keywords
        "shortened_url", "suspicious_tld" -> R.string.overlay_reason_risky_link
        "suspicious" -> R.string.overlay_reason_suspicious_pattern
        else -> spamTypeLabelRes(normalized)
    }
}

internal fun looksLikeInternalOverlayReason(reason: String): Boolean = reason.matches(Regex("[a-z0-9]+(?:_[a-z0-9]+)+"))

internal data class CallerIdOverlayPalette(
    val background: Int,
    val surfaceVariant: Int,
    val primary: Int,
    val error: Int,
    val blue: Int,
    val warning: Int,
    val peach: Int,
    val lavender: Int,
    val text: Int,
    val subtext: Int,
    val overlay: Int,
    val isLight: Boolean,
)

private fun withAlpha(
    color: Int,
    alpha: Int,
): Int = (color and 0x00FFFFFF) or (alpha shl 24)

internal fun overlayPaletteFor(
    themeMode: AppThemeMode,
    systemDark: Boolean,
): CallerIdOverlayPalette {
    val palette = paletteFor(themeMode, systemDark)
    return CallerIdOverlayPalette(
        // Opaque: the overlay sits over whatever app is open, and at 96% its
        // bright text showed through the caller's name and verdict.
        background = palette.surface.toArgb(),
        surfaceVariant = palette.surfaceVariant.toArgb(),
        primary = palette.primary.toArgb(),
        error = palette.error.toArgb(),
        blue = palette.blue.toArgb(),
        warning = palette.warning.toArgb(),
        peach = palette.peach.toArgb(),
        lavender = palette.lavender.toArgb(),
        text = palette.text.toArgb(),
        subtext = palette.subtext.toArgb(),
        overlay = palette.overlay.toArgb(),
        isLight = palette.isLight,
    )
}

private fun Context.currentOverlayPalette(): CallerIdOverlayPalette {
    val themeMode = AppThemeMode.fromStorage(SpamRepository.cachedAppTheme(this))
    val systemDark =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    return overlayPaletteFor(themeMode, systemDark)
}

/**
 * Real-time caller ID overlay with live multi-source spam lookup.
 *
 * Shows immediately with area code, then queries the live lookup sources
 * (SkipCalls, the one that still answers) in parallel. Updates the overlay
 * as each source responds. Shows aggregate spam score + Google search button.
 *
 * RTT calls never reach this service: [CallShieldScreeningService] explicitly
 * allows [android.telecom.Call.Details.PROPERTY_RTT] sessions before it can
 * launch an overlay. This service does not alter Telecom call state or audio;
 * the optional SIT-tone control is available only on the non-RTT surface.
 */
@Suppress("DEPRECATION")
class CallerIdOverlayService : Service() {
    companion object {
        internal const val EXTRA_OUTGOING_RISK_WARNING = "outgoing_risk_warning"
        internal const val EXTRA_LIVE_ENRICHMENT = "live_caller_enrichment"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_CONFIDENCE = "confidence"
        private const val EXTRA_REASON = "reason"
        private const val FAST_SPAM_HIT_TIMEOUT_MS = 1_500L
        private const val AUTO_DISMISS_MS = 20_000L

        /** How long the Blocked strip offers Undo before the overlay closes. */
        private const val UNDO_WINDOW_MS = 6_000L

        /** The report waits past the window, so Undo can't lose a race with it. */
        private const val REPORT_DELAY_MS = UNDO_WINDOW_MS + 1_000L
        private const val UNDONE_LINGER_MS = 1_500L
        internal const val ACTION_WITHDRAW = "com.sysadmindoc.callshield.action.WITHDRAW_OVERLAY"

        // The caller the last incoming overlay was started for, so a call that
        // screening rejects afterwards can take that overlay back.
        @Volatile private var incomingStartedFor: String? = null

        internal fun startIncoming(
            context: Context,
            number: String,
            confidence: Int,
            reason: String,
            liveEnrichment: Boolean = false,
        ) {
            context.startService(
                Intent(context, CallerIdOverlayService::class.java).apply {
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_CONFIDENCE, confidence)
                    putExtra(EXTRA_REASON, reason)
                    putExtra(EXTRA_LIVE_ENRICHMENT, liveEnrichment)
                },
            )
            incomingStartedFor = number
        }

        /**
         * Takes back the incoming overlay started for [number] when screening
         * rejects the call after all. Telecom never adds a rejected call, so no
         * call-state change follows and the overlay would stay up until its
         * 20 s backstop. Service starts arrive in order, so this always lands
         * after the start it withdraws.
         */
        internal fun withdrawIncoming(
            context: Context,
            number: String,
        ) {
            if (number.isEmpty() || incomingStartedFor != number) return
            incomingStartedFor = null
            context.startService(
                Intent(context, CallerIdOverlayService::class.java)
                    .setAction(ACTION_WITHDRAW)
                    .putExtra(EXTRA_NUMBER, number),
            )
        }

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

    // Call-state watchers so the overlay dismisses when the call actually ends,
    // instead of lingering for the full 20 s backstop: one per watched line,
    // each with the manager it was registered on. Held as Any so the API 31+
    // TelephonyCallback type is never referenced on older devices.
    private val callStateWatchers = mutableListOf<Pair<android.telephony.TelephonyManager, Any>>()

    @Volatile private var isOverlayActive = false

    @Volatile private var activeSessionId = 0L

    // What the active session is showing, so a later start for the SAME
    // caller with LESS information can be ignored instead of tearing the
    // session down. Concretely: a generic area-code start (confidence 0)
    // arriving after a "Possible spam N%" warning for the same caller would
    // otherwise destroy the warning before the user could read it and
    // restart the live lookups from scratch.
    @Volatile private var activeNumber: String = ""

    @Volatile private var activeConfidence = 0

    // The newest start delivered. Closing an overlay stops the service only
    // for this start, so a start queued behind it (the next caller's overlay
    // right after a withdrawal) keeps the service running instead of being
    // torn down as soon as it shows.
    private var latestStartId = 0

    // UI elements we need to update
    private var headerText: TextView? = null
    private var scoreText: TextView? = null
    private var statusText: TextView? = null
    private var sourcesContainer: LinearLayout? = null
    private var progressBar: ProgressBar? = null

    // Block swaps the buttons for a "Blocked" strip that offers Undo.
    private var actionRow: LinearLayout? = null
    private var sitToneButton: Button? = null
    private var blockedStrip: LinearLayout? = null
    private var blockedStripText: TextView? = null
    private var blockedStripUndo: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: ""
        val confidence = intent?.getIntExtra(EXTRA_CONFIDENCE, 0) ?: 0
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: ""
        val outgoingRiskWarning = intent?.getBooleanExtra(EXTRA_OUTGOING_RISK_WARNING, false) ?: false
        val liveEnrichmentEnabled =
            !outgoingRiskWarning &&
                shouldRunLiveCallerEnrichment(
                    confidence,
                    intent?.getBooleanExtra(EXTRA_LIVE_ENRICHMENT, false) ?: false,
                )
        val palette = currentOverlayPalette()

        if (number.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_WITHDRAW) {
            if (!isOverlayActive) {
                stopSelf(startId)
            } else if (number == activeNumber) {
                dismiss(activeSessionId)
            }
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
            showOverlay(number, confidence, reason, outgoingRiskWarning, liveEnrichmentEnabled, palette)
                ?: return START_NOT_STICKY
        if (liveEnrichmentEnabled) {
            // Remote enrichment is explicit opt-in and only starts when the
            // local heuristic pipeline has already identified suspicion.
            runLiveLookups(number, sessionId, palette)
        }
        return START_NOT_STICKY
    }

    /** The lines under the number that say something about the caller. None of them is a verdict. */
    private fun LinearLayout.addCallerLabels(
        number: String,
        confidence: Int,
        displayReason: String,
        outgoingRiskWarning: Boolean,
        palette: CallerIdOverlayPalette,
    ) {
        // Where the caller dialed from, when that's another country.
        // A label only, like the carrier's "Overseas call".
        CallerCountry
            .abroad(number, PhoneIdentityCanonicalizer.cachedFromContext(context).homeRegionIso)
            ?.takeUnless { outgoingRiskWarning }
            ?.let { country ->
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.caller_abroad, CallerCountry.displayName(country))
                        setTextColor(palette.subtext)
                        textSize = OVERLAY_TEXT_SP
                    },
                )
            }
        if (displayReason.isNotEmpty() && (confidence <= 0 || outgoingRiskWarning)) {
            addView(
                TextView(context).apply {
                    text = displayReason
                    setTextColor(palette.subtext)
                    textSize = OVERLAY_TEXT_SP
                },
            )
        }
        // Pending community reports are a label, never a verdict: the
        // watch list alone can't block, so this is the only place an
        // otherwise clear caller shows it.
        val watchReporters =
            if (outgoingRiskWarning) 0 else SpamHeuristics.communityWatchReporterCount(context, number)
        if (watchReporters >= SpamHeuristics.COMMUNITY_WATCH_MIN_REPORTERS) {
            addView(
                TextView(context).apply {
                    text =
                        context.resources.getQuantityString(
                            R.plurals.community_watch_label,
                            watchReporters,
                            watchReporters,
                        )
                    setTextColor(palette.warning)
                    textSize = OVERLAY_TEXT_SP
                },
            )
        }
        // A published line rings when the carrier verified it. Name the
        // owner, and warn, because the caller still controls the call.
        OfficialLines.organization(number)?.takeUnless { outgoingRiskWarning }?.let { organization ->
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.overlay_official_line, organization)
                    setTextColor(palette.warning)
                    textSize = OVERLAY_TEXT_SP
                },
            )
        }
    }

    private fun showOverlay(
        number: String,
        confidence: Int,
        reason: String,
        outgoingRiskWarning: Boolean,
        liveEnrichmentEnabled: Boolean,
        palette: CallerIdOverlayPalette,
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
        val displayReason = localizedOverlayReason(reason)
        val sessionId = SystemClock.elapsedRealtimeNanos()

        overlayView =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Rounded bottom corners with premium surface
                background =
                    GradientDrawable().apply {
                        setColor(palette.background)
                        val r = context.overlayDpF(OVERLAY_CORNER_DP * OVERLAY_DESIGN_DENSITY)
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
                        setBackgroundColor(withAlpha(palette.primary, 0x38))
                    },
                )

                // Header — updates based on lookup results
                headerText =
                    TextView(context).apply {
                        text = context.getString(overlayInitialHeaderRes(outgoingRiskWarning, confidence))
                        setTextColor(if (confidence > 0) palette.error else palette.primary)
                        textSize = OVERLAY_TEXT_SP
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.12f
                        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                addView(headerText)

                // Number + location
                addView(
                    TextView(context).apply {
                        text = formatted
                        setTextColor(palette.text)
                        textSize = 24f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = -0.02f
                        setPadding(0, context.overlayDp(8f), 0, context.overlayDp(2f))
                    },
                )
                addCallerLabels(number, confidence, displayReason, outgoingRiskWarning, palette)

                // Score — updates live
                scoreText =
                    TextView(context).apply {
                        text = initialScoreText(confidence, displayReason, outgoingRiskWarning, liveEnrichmentEnabled)
                        setTextColor(palette.peach)
                        textSize = OVERLAY_TEXT_SP
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, context.overlayDp(10f), 0, 0)
                        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                addView(scoreText)

                // Loading indicator
                progressBar =
                    ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                        setPadding(0, context.overlayDp(10f), 0, 0)
                        visibility = goneIf(outgoingRiskWarning || !liveEnrichmentEnabled)
                    }
                addView(progressBar)

                // Sources container — results appear here as they come in
                sourcesContainer =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, context.overlayDp(6f), 0, 0)
                    }
                addView(sourcesContainer)

                // Status text
                statusText =
                    TextView(context).apply {
                        text = context.getString(overlayInitialStatusRes(outgoingRiskWarning, liveEnrichmentEnabled))
                        setTextColor(palette.overlay)
                        textSize = OVERLAY_TEXT_SP
                        letterSpacing = 0.02f
                        setPadding(0, context.overlayDp(6f), 0, 0)
                    }
                addView(statusText)

                // Buttons — row 1
                addView(actionRowView(context, palette, number, digits, outgoingRiskWarning, sessionId))

                // Buttons — row 2: SIT tone
                addView(sitToneButtonView(context, palette, outgoingRiskWarning))

                // In place of the buttons after Block, while Undo is offered
                addView(blockedStripView(context, palette, sessionId))
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
        // dismisses sooner, right when the call ends, so a 2-3 s silenced call's
        // overlay doesn't hover over unrelated UI for the full 20 s. A call that
        // screening rejects never rings and gets no overlay, and one still up
        // for the same caller is closed by withdrawIncoming().
        scheduleDismiss(sessionId, AUTO_DISMISS_MS)
        registerCallStateWatcher(sessionId, outgoingRiskWarning)
        return sessionId
    }

    /** The score line before any live lookup answers. */
    private fun initialScoreText(
        confidence: Int,
        displayReason: String,
        outgoingRiskWarning: Boolean,
        liveEnrichmentEnabled: Boolean,
    ): String =
        when {
            outgoingRiskWarning -> getString(R.string.overlay_outgoing_risk_score, confidence)
            confidence > 0 -> overlayVerdictLine(this, confidence, displayReason)
            liveEnrichmentEnabled -> getString(R.string.overlay_score_loading)
            else -> getString(R.string.overlay_score_local_clear)
        }

    /** Search, Block and Dismiss, in one row under the overlay's text. */
    private fun actionRowView(
        context: Context,
        palette: CallerIdOverlayPalette,
        number: String,
        digits: String,
        outgoingRiskWarning: Boolean,
        sessionId: Long,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            actionRow = this
            setPadding(0, context.overlayDp(16f), 0, 0)

            // Google search
            addView(
                context.overlayButton(R.string.overlay_action_search, palette.blue, palette.surfaceVariant, 8f).apply {
                    setOnClickListener {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(overlaySearchUrl(context, digits))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        } catch (_: Exception) {
                        }
                        dismiss(sessionId)
                    }
                },
            )

            addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

            // Block button
            addView(
                context.overlayButton(R.string.overlay_action_block, palette.error, palette.surfaceVariant, 8f).apply {
                    visibility = goneIf(outgoingRiskWarning)
                    setOnClickListener {
                        isEnabled = false
                        blockFromOverlay(number, sessionId)
                    }
                },
            )

            addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

            // Dismiss
            addView(
                context.overlayButton(R.string.overlay_action_dismiss, palette.overlay, Color.TRANSPARENT, 8f).apply {
                    setOnClickListener { dismiss(sessionId) }
                },
            )
        }

    /** The SIT tone button, on its own row under the others. */
    private fun sitToneButtonView(
        context: Context,
        palette: CallerIdOverlayPalette,
        outgoingRiskWarning: Boolean,
    ): Button =
        context.overlayButton(R.string.overlay_action_sit_tone, palette.subtext, palette.surfaceVariant, 6f, minTouchWidth = false).apply {
            sitToneButton = this
            visibility = goneIf(outgoingRiskWarning)
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = context.overlayDp(8f) }
            setOnClickListener { playSitToneFromOverlay() }
        }

    private fun localizedOverlayReason(reason: String): String =
        overlayReasonLabelRes(reason)?.let(::getString)
            ?: if (looksLikeInternalOverlayReason(reason)) {
                getString(R.string.overlay_reason_suspicious_pattern)
            } else {
                reason
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
            val lines = callStateLines(tm)
            // Both registration APIs deliver the *current* state immediately.
            // Incoming overlays register while the phone is RINGING, but an
            // outgoing warning registers during call setup where the radio can
            // still report IDLE — acting on that snapshot would dismiss the
            // warning before the user can read it. Outgoing sessions therefore
            // ignore IDLE until a non-idle state has been observed.
            val shouldDismissOnIdle = idleDismissGate(outgoingRiskWarning, lines.size)
            lines.forEachIndexed { line, lineManager ->
                val onState: (Int) -> Unit = { state ->
                    if (shouldDismissOnIdle(line, state)) {
                        dismiss(sessionId)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    registerModernCallStateWatcher(lineManager, onState)
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
                                onState(state)
                            }
                        }
                    callStateWatchers += lineManager to listener
                    @Suppress("DEPRECATION")
                    lineManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        } catch (_: Exception) {
            // Best-effort — the 20 s handler backstop guarantees dismissal.
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun registerModernCallStateWatcher(
        tm: android.telephony.TelephonyManager,
        onState: (Int) -> Unit,
    ) {
        val callback =
            object :
                android.telephony.TelephonyCallback(),
                android.telephony.TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onState(state)
                }
            }
        callStateWatchers += tm to callback
        tm.registerTelephonyCallback(mainExecutor, callback)
    }

    /**
     * The managers to watch: one per active SIM when there are several, else
     * the default one. The default manager's first report is the default
     * SIM's own state, so on a dual-SIM phone a call ringing on the other SIM
     * read IDLE and closed its overlay at once; a per-SIM manager reports its
     * own SIM throughout. Screening can't pick the call's own SIM instead,
     * because Telecom hands screening apps a null account handle. Needs
     * READ_PHONE_STATE, which [registerCallStateWatcher] has already checked.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun callStateLines(tm: android.telephony.TelephonyManager): List<android.telephony.TelephonyManager> {
        val subscriptions =
            try {
                getSystemService(android.telephony.SubscriptionManager::class.java)
                    ?.activeSubscriptionInfoList
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        if (subscriptions.size < 2) return listOf(tm)
        return subscriptions.map { tm.createForSubscriptionId(it.subscriptionId) }
    }

    /**
     * Stateful predicate over [lineCount] watched lines: IDLE dismisses once
     * every line has reported and all of them read IDLE, so a call on one SIM
     * keeps the overlay up while the other SIM sits idle. For outgoing
     * warnings, IDLE only dismisses after a non-idle state has been seen (the
     * registration snapshot may be IDLE). Incoming overlays keep the original
     * dismiss-on-IDLE behavior, snapshot included: a call that ended before
     * the overlay registered closes it at once.
     */
    private fun idleDismissGate(
        outgoingRiskWarning: Boolean,
        lineCount: Int,
    ): (Int, Int) -> Boolean {
        var sawNonIdle = !outgoingRiskWarning
        val lineStates = arrayOfNulls<Int>(lineCount)
        return { line, state ->
            lineStates[line] = state
            if (state != android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                sawNonIdle = true
                false
            } else {
                sawNonIdle && lineStates.all { it == android.telephony.TelephonyManager.CALL_STATE_IDLE }
            }
        }
    }

    private fun unregisterCallStateWatcher() {
        callStateWatchers.forEach { (tm, watcher) ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    (watcher as? android.telephony.TelephonyCallback)?.let(tm::unregisterTelephonyCallback)
                } else {
                    @Suppress("DEPRECATION")
                    (watcher as? android.telephony.PhoneStateListener)?.let {
                        tm.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        callStateWatchers.clear()
    }

    /** Query all sources in parallel and update the overlay as each responds. */
    private fun runLiveLookups(
        number: String,
        sessionId: Long,
        palette: CallerIdOverlayPalette,
    ) {
        val spamSources = ExternalLookup.spamLookupSources()
        val totalSources = spamSources.size
        val tally = LiveLookupTally()

        fun scoreFor(snapshot: LiveLookupSnapshot): Int =
            liveLookupScore(
                totalReports = snapshot.totalReports,
                anySpam = snapshot.anySpam,
                allFinished = snapshot.completed >= totalSources,
                definitive = snapshot.definitive,
            )

        fun renderScore(snapshot: LiveLookupSnapshot) {
            val score = scoreFor(snapshot)
            if (score >= 0) {
                val color = overlayScoreColor(score, palette)
                scoreText?.text =
                    this@CallerIdOverlayService.getString(
                        R.string.overlay_spam_score,
                        score,
                        if (snapshot.totalReports == 0 && snapshot.anySpam) {
                            // A verdict without a count, which is all SkipCalls gives.
                            this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                        } else {
                            formatReports(snapshot.totalReports)
                        },
                    )
                scoreText?.setTextColor(color)
                headerText?.text = this@CallerIdOverlayService.getString(overlayScoredHeaderRes(score))
                headerText?.setTextColor(color)
            }
            if (snapshot.completed >= totalSources) {
                progressBar?.visibility = android.view.View.GONE
                statusText?.text = this@CallerIdOverlayService.getString(overlayFinishedStatusRes(snapshot))
            }
        }

        fun addSourceResult(result: ExternalLookup.SourceResult) {
            val snapshot = tally.record(result)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                sourcesContainer?.addView(
                    TextView(this).apply {
                        val info =
                            when {
                                result.reports > 0 -> formatReports(result.reports)
                                result.isSpam -> this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                                else -> sourceStatusText(result)
                            }
                        text = this@CallerIdOverlayService.getString(R.string.overlay_source_result, overlaySourceIcon(result), result.source, info)
                        setTextColor(overlaySourceColor(result, palette))
                        textSize = OVERLAY_TEXT_SP
                        setPadding(0, context.overlayDp(3f), 0, context.overlayDp(3f))
                    },
                )
                renderScore(snapshot)
            }
        }

        fun markSourceFinished() {
            val snapshot = tally.record(null)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                renderScore(snapshot)
            }
        }

        fun publishWarmHit(result: ExternalLookup.SourceResult) {
            if (!tally.claimWarmHit()) return

            val score =
                scoreFor(
                    LiveLookupSnapshot(
                        completed = totalSources,
                        totalReports = result.reports,
                        anySpam = result.isSpam || result.reports >= 3,
                        definitive = 1,
                    ),
                ).coerceAtLeast(50)
            handler.post {
                if (!isCurrentSession(sessionId)) return@post
                val color = overlayScoreColor(score, palette)
                val reportText =
                    if (result.reports > 0) {
                        formatReports(result.reports)
                    } else {
                        this@CallerIdOverlayService.getString(R.string.overlay_source_flagged)
                    }
                headerText?.text = this@CallerIdOverlayService.getString(R.string.overlay_header_likely_spam)
                headerText?.setTextColor(color)
                scoreText?.text = this@CallerIdOverlayService.getString(R.string.overlay_spam_score, score, reportText)
                scoreText?.setTextColor(color)
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
                    R.string.remote_lookup_status_found
                }

                RemoteLookupStatus.CLEAN -> {
                    R.string.remote_lookup_status_clean
                }

                RemoteLookupStatus.UNCATEGORIZED -> {
                    R.string.remote_lookup_status_uncategorized
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

                RemoteLookupStatus.OFFLINE_MODE -> {
                    R.string.remote_lookup_status_offline_mode
                }
            }
        return getString(statusRes)
    }

    /**
     * Block the overlay's caller off the main thread, then offer Undo on a
     * strip; the community report goes out only after the Undo window.
     * appScope has no CoroutineExceptionHandler: an uncaught SQLiteException
     * (disk full, corruption — a failure class this app explicitly self-heals
     * elsewhere) would kill the whole process mid-call. Same guard pattern as
     * SpamActionReceiver.
     */
    private fun blockFromOverlay(
        number: String,
        sessionId: Long,
    ) {
        // The strip outlives the call, so a caller who hangs up right after
        // the tap doesn't take the Undo window with them.
        unregisterCallStateWatcher()
        val description = getString(R.string.desc_blocked_from_overlay)
        val appContext = applicationContext
        CallShieldApp.appScope.launch {
            try {
                val repository = SpamRepository.getInstance(appContext)
                val undo = repository.blockNumberUndoable(number, "spam", description)
                val normalized = repository.normalizeNumber(number)
                val send: suspend () -> Unit = {
                    try {
                        com.sysadmindoc.callshield.data.CommunityContributor
                            .contribute(appContext, normalized, "spam")
                    } catch (e: Exception) {
                        android.util.Log.w("CallerIdOverlay", "Overlay report failed", e)
                    }
                }
                if (undo == null) {
                    // Nothing changed, so there's nothing to undo.
                    send()
                    handler.post { dismiss(sessionId) }
                } else {
                    handler.post { showBlockedStrip(sessionId, undo, send) }
                }
            } catch (e: Exception) {
                android.util.Log.w("CallerIdOverlay", "Overlay block failed", e)
                handler.post { dismiss(sessionId) }
            }
        }
    }

    /** The "Blocked" line with Undo that replaces the buttons after Block. */
    private fun blockedStripView(
        context: Context,
        palette: CallerIdOverlayPalette,
        sessionId: Long,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, context.overlayDp(16f), 0, 0)
            blockedStrip = this
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.overlay_blocked_status)
                    setTextColor(palette.error)
                    textSize = OVERLAY_TEXT_SP
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    blockedStripText = this
                },
            )
            addView(
                context.overlayButton(R.string.blocked_log_undo, palette.primary, palette.surfaceVariant, 8f).apply {
                    blockedStripUndo = this
                },
            )
            addView(
                context.overlayButton(R.string.overlay_action_dismiss, palette.overlay, Color.TRANSPARENT, 8f).apply {
                    setOnClickListener { dismiss(sessionId) }
                },
            )
        }

    private fun showBlockedStrip(
        sessionId: Long,
        undo: BlocklistRepository.BlockUndo,
        send: suspend () -> Unit,
    ) {
        if (!isCurrentSession(sessionId)) {
            // The popup closed before the block landed, so nothing can undo it.
            CallShieldApp.appScope.launch { send() }
            return
        }
        // Started with the strip, so the report always waits out the window
        // however late this runs on the main thread.
        val report = DeferredReport(CallShieldApp.appScope, wait = { delay(REPORT_DELAY_MS) }, send = send)
        actionRow?.visibility = android.view.View.GONE
        sitToneButton?.visibility = android.view.View.GONE
        blockedStrip?.visibility = android.view.View.VISIBLE
        blockedStripUndo?.setOnClickListener { button ->
            button.visibility = android.view.View.GONE
            blockedStripText?.text = getString(R.string.overlay_block_undone)
            report.cancel()
            val appContext = applicationContext
            CallShieldApp.appScope.launch {
                try {
                    SpamRepository.getInstance(appContext).undoBlock(undo)
                } catch (e: Exception) {
                    android.util.Log.w("CallerIdOverlay", "Overlay undo failed", e)
                }
            }
            scheduleDismiss(sessionId, UNDONE_LINGER_MS)
        }
        scheduleDismiss(sessionId, UNDO_WINDOW_MS)
    }

    private fun scheduleDismiss(
        sessionId: Long,
        delayMillis: Long,
    ) {
        clearDismissCallback()
        val runnable = Runnable { dismiss(sessionId) }
        dismissRunnable = runnable
        handler.postDelayed(runnable, delayMillis)
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
        stopSelf(latestStartId)
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
        sourcesContainer = null
        progressBar = null
        actionRow = null
        sitToneButton = null
        blockedStrip = null
        blockedStripText = null
        blockedStripUndo = null
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
