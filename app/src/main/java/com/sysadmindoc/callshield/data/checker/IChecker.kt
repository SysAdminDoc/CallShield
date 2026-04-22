package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.SpamRepository

/**
 * Priority-sorted detection pipeline.
 *
 * Every detection layer — whitelist, contacts, GitHub DB, prefix, wildcard,
 * ML scorer, heuristics, campaign burst, push-alert bridge, time block, etc. —
 * implements [IChecker] and returns a [BlockResult] if it has an opinion, or
 * `null` to pass the decision to the next checker.
 *
 * The pipeline sorts checkers by [priority] descending, runs them in order,
 * and returns the first non-null result (see [CheckerPipeline.run]). A
 * terminal catch-all at `Int.MIN_VALUE` guarantees every call terminates.
 *
 * Inspired by SpamBlocker's `IChecker` architecture (github.com/aj3423/SpamBlocker)
 * — the cleanest pattern we found in the OSS landscape for keeping detection
 * logic modular, testable, and extensible.
 *
 * ## Hot-path contract
 *
 * [check] runs inside the CallScreeningService 5-second budget.
 * Implementations MUST:
 *   - Read from [CheckContext.prefs] (a pre-loaded snapshot), never
 *     re-open `DataStore.data.first()`.
 *   - Use [CheckContext.timeLeftMillis] to skip or shortcut work when time is tight.
 *   - Not block on network calls unless racing with a budget (see Race.kt).
 */
interface IChecker {
    /**
     * Higher runs first. Keep a stable numeric ladder so adding a new
     * checker between two existing priorities is a one-line change.
     */
    val priority: Int

    /**
     * A short, stable identifier used in logs and in [BlockResult.matchSource]
     * when the checker produces a block. Must be snake_case ASCII — it is
     * persisted in the blocked-call log.
     */
    val name: String

    /**
     * Optional gate — returning `false` causes the pipeline to skip this
     * checker without invoking [check]. Used for settings toggles and
     * schedule/SIM-slot gating (see future HashWildcardChecker.isEnabled).
     *
     * Default: always enabled.
     */
    suspend fun isEnabled(ctx: CheckContext): Boolean = true

    /**
     * Run the detection layer. Return:
     *   - [BlockResult.block] → the call/SMS is spam, stop the pipeline.
     *   - [BlockResult.allow] → the call/SMS is trusted, stop the pipeline
     *     (e.g. whitelist, contacts, recently-dialed, push-alert).
     *   - `null` → no opinion, continue.
     */
    suspend fun check(ctx: CheckContext): BlockResult?
}

/**
 * Per-call context. Built once per incoming call/SMS and passed through the
 * entire pipeline so checkers share a consistent view of prefs, timing, and
 * derived values instead of each fetching its own.
 *
 * Construction is cheap; do it on every incoming call.
 */
data class CheckContext(
    val appContext: Context,
    /** The digit-only, `+`-prefixed normalized number. Never blank. */
    val number: String,
    /** SMS message body, null for calls. */
    val smsBody: String? = null,
    /**
     * When `false`, checkers must not record campaign state, pop overlays,
     * or mutate any "this is a live call" telemetry — the call happened in
     * the past and is being re-scored by [com.sysadmindoc.callshield.service.CallLogScanner]
     * or [com.sysadmindoc.callshield.service.SmsInboxScanner].
     */
    val realtimeCall: Boolean,
    /** Pre-loaded DataStore snapshot — share across all checkers. */
    val prefs: Preferences,
    /**
     * STIR/SHAKEN carrier verification status when available
     * ([android.telecom.Connection.VERIFICATION_STATUS_FAILED] etc).
     * `null` when the caller can't supply one — historical scans, SMS, or
     * pre-Android-11 devices. Consumed by [StirShakenChecker]; every other
     * checker ignores this field.
     */
    val verificationStatus: Int? = null,
    /** Entry epoch for budget accounting. */
    val startTimeMillis: Long = System.currentTimeMillis(),
) {
    /**
     * Milliseconds remaining before the 5-second Android CallScreeningService
     * deadline. Effective budget is 4500 ms — 500 ms buffer for the response
     * to actually arrive at the telecom stack.
     *
     * Checkers that do network I/O MUST compare against this and skip when
     * `timeLeftMillis() <= 0`.
     */
    fun timeLeftMillis(budgetMs: Long = 4500L): Long {
        val elapsed = System.currentTimeMillis() - startTimeMillis
        return (budgetMs - elapsed).coerceAtLeast(0)
    }
}

/**
 * The outcome of a checker.
 *
 * A checker returns `null` to pass, or constructs a [BlockResult] via
 * [block] or [allow]. The two factories match the two ways a checker can
 * stop the pipeline early.
 */
data class BlockResult(
    val shouldBlock: Boolean,
    /** Stable identifier of the layer that decided. Matches [IChecker.name]. */
    val matchSource: String,
    /** Coarse type ("robocall", "sms_spam", "spoofed", "manual_whitelist"…). */
    val type: String = "",
    /** Human-readable reason, shown in the block log and detail screen. */
    val description: String = "",
    /** 0-100; unused for allow results. */
    val confidence: Int = 100,
) {
    companion object {
        fun block(matchSource: String, type: String = "", description: String = "", confidence: Int = 100) =
            BlockResult(true, matchSource, type, description, confidence)

        fun allow(matchSource: String) =
            BlockResult(false, matchSource)
    }
}

/**
 * A stable priority ladder. Higher runs first. Leave headroom between
 * numbers so new checkers can be inserted without renumbering.
 *
 * Keeping the ladder in a single object (rather than scattering magic
 * numbers across 11 files) prevents accidental reordering and makes the
 * full detection order reviewable at a glance.
 */
object CheckerPriority {
    // ── Top-priority allows — can override every block below ─────────
    const val MANUAL_WHITELIST      = 10_000   // user-added entries; emergency contacts
    const val CONTACT_WHITELIST     =  9_000   // device contacts lookup

    // ── Carrier-signed verdict — strong block but MUST sit under the
    // explicit-allow tier above, otherwise a whitelisted emergency
    // contact on a non-STIR carrier is hard-rejected (v1.6.0 bug).
    const val STIR_SHAKEN           =  8_500

    // ── Explicit blocks — must NOT be overridden by recently-dialed ──
    // or push-alert. If the user intentionally blocked or wildcarded
    // this number, that intent is authoritative.
    const val USER_BLOCKLIST        =  7_000   // merged with GITHUB_DATABASE in DatabaseChecker
    const val SYSTEM_BLOCK_LIST     =  6_900   // reserved for A4 — BlockedNumberContract
    const val PREFIX_MATCH          =  6_000
    const val WILDCARD_RULE         =  5_500
    const val HASH_WILDCARD_RULE    =  5_400   // reserved for A5 — length-locked # patterns

    // ── Conditional allows — trust signals that sit UNDER explicit
    // user blocks but ABOVE weaker detection layers.
    const val RECENTLY_DIALED       =  5_000   // user just called this number
    const val REPEATED_URGENT       =  4_900   // same number called 2+ times in 5 min
    const val PUSH_ALERT_BRIDGE     =  4_700   // reserved for A3 — notification-bridged allow
    const val CAMPAIGN_RECORDER     =  4_500   // side-effect only (records into in-memory map)

    // ── Weaker blocks (statistical / heuristic / temporal) ───────────
    const val TIME_BLOCK            =  4_000
    const val FREQUENCY_ESCALATION  =  3_500
    const val HEURISTIC             =  3_000
    const val CAMPAIGN_BURST        =  2_500
    const val ML_SCORER             =  2_000

    // ── Catch-all ─────────────────────────────────────────────────────
    const val PASS_BY_DEFAULT       =  Int.MIN_VALUE
}

/**
 * Run a set of checkers priority-descending, return the first non-null
 * result. If every checker passes, returns `null` — callers map `null` to
 * their own "not spam" default.
 *
 * The pipeline does NOT sort on every run — callers should pass an
 * already-sorted list (see [SpamCheckers.sorted]) to keep the hot path fast.
 */
object CheckerPipeline {
    suspend fun run(sortedCheckers: List<IChecker>, ctx: CheckContext): BlockResult? {
        for (checker in sortedCheckers) {
            // Budget insurance: if a slow checker earlier in the chain
            // chewed through the 4.5 s window, bail instead of racing past
            // the 5 s Android deadline. Returning null maps to "not spam"
            // in the caller — the safer default under time pressure is to
            // allow the call rather than let Android auto-allow on timeout
            // without our telemetry.
            if (ctx.timeLeftMillis() <= 0L) return null
            if (!checker.isEnabled(ctx)) continue
            val result = try {
                checker.check(ctx)
            } catch (e: Exception) {
                // A buggy checker must never take down the pipeline — log
                // nothing (we're on the hot path) and continue. SpamRepository
                // keeps working with one fewer detection layer.
                null
            }
            if (result != null) return result
        }
        return null
    }
}

/**
 * Factory for the canonical checker set plus SMS-only extensions.
 *
 * Held as a lazy-initialized list on [SpamRepository] so we build the
 * pipeline once per process, not per call.
 */
object SpamCheckers {
    /** Call + SMS shared detection chain. Call this from [SpamRepository.init]. */
    fun buildCallChain(repo: SpamRepository, appContext: Context): List<IChecker> =
        buildList {
            add(WhitelistChecker(repo))
            add(ContactWhitelistChecker(appContext))
            add(StirShakenChecker())
            add(DatabaseChecker(repo))
            add(SystemBlockListChecker(appContext))
            add(PrefixChecker(repo))
            add(WildcardChecker(repo))
            add(HashWildcardChecker(repo))
            add(RecentlyDialedChecker(appContext))
            add(RepeatedUrgentChecker(appContext))
            add(PushAlertChecker())                // notification-bridged allow
            add(CampaignRecorderChecker())         // not a blocker — records for campaign detection
            add(TimeBlockChecker())
            add(FrequencyEscalationChecker(repo))
            add(HeuristicChecker(repo, appContext))
            add(CampaignBurstChecker())
            add(MlScorerChecker())
        }.sortedByDescending { it.priority }

    /** SMS-specific extensions appended after the shared chain returns null. */
    fun buildSmsExtensions(repo: SpamRepository, appContext: Context): List<IChecker> =
        buildList {
            add(SmsContextChecker_Checker(appContext))
            add(SmsKeywordChecker(repo))
            add(SmsContentChecker())
        }.sortedByDescending { it.priority }
}
