package com.sysadmindoc.callshield.data.checker

import android.content.Context
import android.content.Intent
import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CampaignDetector
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SmsContextChecker
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.SystemBlockList
import com.sysadmindoc.callshield.data.repository.SpamRepositoryImpl
import com.sysadmindoc.callshield.service.CallerIdOverlayService
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────────
// Allow-side checkers
// ─────────────────────────────────────────────────────────────────────

/**
 * User-added whitelist — the highest-priority allow. Emergency-flagged
 * entries surface with `emergency_contact` matchSource so the block log
 * and detail screen can distinguish them.
 */
internal class WhitelistChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.MANUAL_WHITELIST
    override val name = "manual_whitelist"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val entry = repo.findWhitelistEntryInternal(ctx.number) ?: return null
        return BlockResult.allow(if (entry.isEmergency) "emergency_contact" else "manual_whitelist")
    }
}

/**
 * Device contacts — if the caller is in the user's address book they are
 * never spam. Gated by the `contact_whitelist_enabled` setting because
 * some users sync thousands of contacts they don't actively trust.
 */
internal class ContactWhitelistChecker(
    private val appContext: Context,
    private val spamHeuristics: SpamHeuristics,
) : IChecker {
    override val priority = CheckerPriority.CONTACT_WHITELIST
    override val name = "contact_whitelist"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_CONTACT_WHITELIST] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (spamHeuristics.isInContacts(appContext, ctx.number)) {
            BlockResult.allow("contact_whitelist")
        } else {
            null
        }
}

/**
 * Contacts-only mode — blocks all calls from numbers not in the device
 * address book. Gated by `KEY_CONTACTS_ONLY` (default OFF).
 *
 * Sits below CONTACT_WHITELIST (so contacts are allowed first) and above
 * STIR_SHAKEN and all explicit user rules. When active, only contacts
 * and manual-whitelist entries ring through.
 */
internal class ContactsOnlyChecker(
    private val appContext: Context,
    private val spamHeuristics: SpamHeuristics,
) : IChecker {
    override val priority = CheckerPriority.CONTACTS_ONLY
    override val name = "contacts_only"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_CONTACTS_ONLY] ?: false

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (!spamHeuristics.isInContacts(appContext, ctx.number)) {
            BlockResult.block("contacts_only", description = "Blocked — contacts-only mode is active")
        } else {
            null
        }
}

/**
 * STIR/SHAKEN attestation-level TRUST allow.
 *
 * Complementary to [StirShakenChecker] (which blocks on `VERIFICATION_STATUS_FAILED`).
 * When the carrier actively signs for the calling number
 * (`VERIFICATION_STATUS_PASSED`, attestation-level A or B in the SHAKEN
 * framework), treat that as a trust signal strong enough to short-circuit
 * the weaker downstream blockers — heuristic, ML, campaign-burst, frequency.
 *
 * Priority slot: [CheckerPriority.STIR_SHAKEN_TRUSTED] = 5_300. This sits
 * BELOW every explicit user rule (manual whitelist, contact whitelist,
 * user blocklist, prefix, wildcard, hash-wildcard, system-block-list) and
 * BELOW the STIR_SHAKEN block — a user who explicitly blocked a number
 * keeps that intent even if the carrier verifies it, and a carrier that
 * says "this specific call is spoofed" beats a generic "we trust this
 * caller's line identity." The allow still lands ABOVE heuristic / ML /
 * campaign-burst / frequency so it does what it's here to do.
 *
 * Rationale (from Round-2 research, aj3423/SpamBlocker + adamff-dev): US
 * wholesale carriers frequently attest "C" on legitimate traffic, so we
 * do NOT treat C as a sole block signal. We only fire the allow on a
 * positive PASSED signal, never on the absence of one.
 *
 * Gated on the user setting and the runtime ability to read a
 * verification status (non-null); skipped for historical scans and SMS.
 */
internal class StirShakenTrustChecker : IChecker {
    override val priority = CheckerPriority.STIR_SHAKEN_TRUSTED
    override val name = "stir_shaken_trusted"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        isEnabledPure(
            settingEnabled = ctx.prefs[SpamRepository.KEY_STIR_TRUSTED_ALLOW] ?: true,
            verificationStatus = ctx.verificationStatus,
        )

    override suspend fun check(ctx: CheckContext): BlockResult? = decidePure(ctx.verificationStatus)

    companion object {
        // android.telecom.Connection.VERIFICATION_STATUS_PASSED == 1 (AOSP).
        // Reproduced here as a plain Int so JVM unit tests can feed the
        // pure helpers without pulling in the android.telecom stub, which
        // throws `RuntimeException("Stub!")` for field reads on unit-test
        // classpath unless testOptions.unitTests.returnDefaultValues = true
        // (which we intentionally don't set — it masks real bugs).
        internal const val VERIFICATION_STATUS_PASSED = 1

        /** Pure-logic helper — testable without a CheckContext or Android. */
        internal fun isEnabledPure(
            settingEnabled: Boolean,
            verificationStatus: Int?,
        ): Boolean = settingEnabled && verificationStatus != null

        /** Pure-logic helper — returns the allow result iff the carrier signed PASSED. */
        internal fun decidePure(verificationStatus: Int?): BlockResult? =
            if (verificationStatus == VERIFICATION_STATUS_PASSED) {
                BlockResult.allow("stir_shaken_trusted")
            } else {
                null
            }
    }
}

/**
 * STIR/SHAKEN — carrier-signed attestation. On Android 11+ the telecom
 * stack exposes [android.telecom.Call.Details.callerNumberVerificationStatus];
 * `VERIFICATION_STATUS_FAILED` is a very strong spam signal (the carrier
 * actively believes the calling number is spoofed).
 *
 * Sits BELOW the manual-whitelist + contact-whitelist tier so users who
 * have explicitly trusted a number — emergency contacts on a legacy
 * PBX, a relative on an old VoIP trunk that hasn't deployed STIR — can
 * still ring through. Moved here from the screening service for exactly
 * this reason after a v1.6.0 code review.
 *
 * Paired with [StirShakenTrustChecker] which handles the PASSED case.
 *
 * Gated on both the user setting and the runtime ability to read a
 * verification status (non-null); skipped for historical scans and SMS.
 */
internal class StirShakenChecker : IChecker {
    override val priority = CheckerPriority.STIR_SHAKEN
    override val name = "stir_shaken_failed"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = (ctx.prefs[SpamRepository.KEY_STIR_SHAKEN] ?: true) && ctx.verificationStatus != null

    override suspend fun check(ctx: CheckContext): BlockResult? {
        @Suppress("DEPRECATION")
        val failed = ctx.verificationStatus == android.telecom.Connection.VERIFICATION_STATUS_FAILED
        return if (failed) {
            BlockResult.block(
                matchSource = "stir_shaken_failed",
                type = "spoofed",
                description = "Carrier could not verify caller identity",
            )
        } else {
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Block-side: explicit user / DB / rule matches
// ─────────────────────────────────────────────────────────────────────

/**
 * Short-lived allow for one-off caller recovery. It lives below permanent
 * explicit blocks but above downloaded exact reputation rows.
 */
internal class TemporaryAllowChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.TEMPORARY_ALLOW
    override val name = "temporary_allow"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        repo.findTemporaryWhitelistEntryInternal(ctx.number) ?: return null
        return BlockResult.allow("temporary_allow")
    }
}

/**
 * Read-only view of Android's system-wide block list (A4). Allows
 * CallShield to respect blocks set by the stock Phone / Messages app
 * without maintaining a bidirectional mirror. Silently no-ops when the
 * app lacks the default-dialer role.
 */
internal class SystemBlockListChecker(
    private val appContext: Context,
) : IChecker {
    override val priority = CheckerPriority.SYSTEM_BLOCK_LIST
    override val name = "system_block_list"

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (SystemBlockList.isBlocked(appContext, ctx.number)) {
            BlockResult.block(
                matchSource = "system_block_list",
                type = "user_blocked",
                description = "Blocked via system block list",
            )
        } else {
            null
        }
}

/**
 * Combined user-blocklist + GitHub database lookup — both live in the
 * same `spam_numbers` table. A single Room query handles both.
 */
internal class UserBlocklistChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.USER_BLOCKLIST
    override val name = "user_blocklist"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val entry = repo.findByNumberInternal(ctx.number)
        return if (entry?.isUserBlocked == true) {
            val source = if (entry.expiresAt == null) "user_blocklist" else "temporary_block"
            val description =
                if (entry.expiresAt == null) {
                    entry.description
                } else {
                    entry.description.ifBlank { "Temporarily blocked" }
                }
            BlockResult.block(source, entry.type, description)
        } else {
            null
        }
    }
}

internal class DatabaseChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.GITHUB_DATABASE
    override val name = "database"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val entry = repo.findByNumberInternal(ctx.number)
        return if (entry != null && !entry.isUserBlocked) {
            BlockResult.block("database", entry.type, entry.description)
        } else {
            null
        }
    }
}

/**
 * Database prefix auto-expansion — when the exact number isn't in the DB
 * but another entry sharing the same prefix (minus last 2 digits) exists,
 * block with reduced confidence. Catches campaign number siblings.
 */
internal class DbPrefixExpansionChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.DB_PREFIX_EXPANSION
    override val name = "db_prefix_expansion"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_DB_PREFIX_EXPANSION] ?: false

    override suspend fun check(ctx: CheckContext): BlockResult? {
        if (!repo.hasDbPrefixMatch(ctx.number)) return null
        return BlockResult.block(
            "db_prefix_expansion",
            description = "Number shares prefix with a known spam entry",
            confidence = 50,
        )
    }
}

/**
 * NPA-NXX (or arbitrary digit-prefix) matcher. Prefixes are loaded once
 * and cached in [SpamRepositoryImpl]; cache invalidation happens on sync.
 */
internal class PrefixChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.PREFIX_MATCH
    override val name = "prefix"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        for (prefix in repo.getPrefixesCachedInternal()) {
            if (ctx.number.startsWith(prefix.prefix)) {
                return BlockResult.block("prefix", prefix.type, prefix.description)
            }
        }
        return null
    }
}

/**
 * Wildcard / regex rules (Feature 8). Cached list; invalidated on edit.
 * A7 schedule gating: rules may carry a day/hour window that skips the
 * (potentially expensive) regex match when inactive.
 */
internal class WildcardChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.WILDCARD_RULE
    override val name = "wildcard"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val rules = repo.getActiveWildcardsCachedInternal()
        if (rules.isEmpty()) return null
        val now = java.util.Calendar.getInstance()
        for (rule in rules) {
            if (rule.matchesNow(ctx.number, now)) {
                return BlockResult.block("wildcard", "blocked", rule.description)
            }
        }
        return null
    }
}

/**
 * Length-locked `#` wildcard rules (A5, Saracroche-style). Cached list;
 * invalidated on edit. Separate from [WildcardChecker] so the two rule
 * types can coexist without one semantically swallowing the other.
 *
 * A7 schedule gating: each rule may carry a day/hour window. We build
 * one [java.util.Calendar] for the whole check so all rules share the
 * same "now" — relevant when a call arrives right at a schedule boundary.
 */
internal class HashWildcardChecker(
    private val repo: SpamRepositoryImpl,
    private val hashWildcardMatcher: HashWildcardMatcher,
) : IChecker {
    override val priority = CheckerPriority.HASH_WILDCARD_RULE
    override val name = "hash_wildcard"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val rules = repo.getActiveHashWildcardsCachedInternal()
        if (rules.isEmpty()) return null
        val now = java.util.Calendar.getInstance()
        for (rule in rules) {
            if (rule.matchesNow(ctx.number, now, hashWildcardMatcher)) {
                val detail = rule.description.ifBlank { rule.pattern }
                return BlockResult.block("hash_wildcard", "blocked", detail)
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────
// Conditional allow-throughs — placed BELOW explicit blocks so
// intentional user rules are not overridden by "we called them recently".
// ─────────────────────────────────────────────────────────────────────

internal class RecentlyDialedChecker(
    private val appContext: Context,
    private val callbackDetector: CallbackDetector,
) : IChecker {
    override val priority = CheckerPriority.RECENTLY_DIALED
    override val name = "recently_dialed"

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (callbackDetector.wasRecentlyDialed(appContext, ctx.number)) {
            BlockResult.allow("recently_dialed")
        } else {
            null
        }
}

internal class AnsweredCallerChecker(
    private val appContext: Context,
    private val callbackDetector: CallbackDetector,
) : IChecker {
    override val priority = CheckerPriority.ANSWERED_CALLER
    override val name = "answered_caller"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_ANSWERED_CALLER_TRUST] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val threshold =
            (
                ctx.prefs[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD]
                    ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD
            ).coerceAtLeast(1)
        val windowDays =
            (
                ctx.prefs[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS]
                    ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS
            ).coerceAtLeast(1)

        return if (callbackDetector.wasAnsweredRepeatedly(appContext, ctx.number, windowDays, threshold)) {
            BlockResult.allow("answered_caller")
        } else {
            null
        }
    }
}

internal class EmergencyCallbackChecker(
    private val appContext: Context,
    private val callbackDetector: CallbackDetector,
) : IChecker {
    override val priority = CheckerPriority.EMERGENCY_CALLBACK
    override val name = "emergency_callback"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val windowMinutes =
            (
                ctx.prefs[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES]
                    ?: CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES
            ).coerceAtLeast(1)

        return if (callbackDetector.hasRecentEmergencyCall(appContext, windowMinutes)) {
            BlockResult.allow("emergency_callback")
        } else {
            null
        }
    }
}

internal class RepeatedUrgentChecker(
    private val appContext: Context,
    private val callbackDetector: CallbackDetector,
) : IChecker {
    override val priority = CheckerPriority.REPEATED_URGENT
    override val name = "repeated_urgent"

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (callbackDetector.isRepeatedUrgentCall(appContext, ctx.number)) {
            BlockResult.allow("repeated_urgent")
        } else {
            null
        }
}

/**
 * Side-effect-only checker that records the call into the in-memory
 * campaign detector. Placed after the recently-dialed / repeated-urgent
 * allows so family / coworkers / repeat-callback numbers don't poison the
 * campaign bucket for their NPA-NXX.
 *
 * Non-realtime invocations (historical scans) skip the record to avoid
 * flagging old prefixes as active campaigns.
 */
internal class CampaignRecorderChecker(
    private val campaignDetector: CampaignDetector,
) : IChecker {
    override val priority = CheckerPriority.CAMPAIGN_RECORDER
    override val name = "campaign_recorder"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        if (ctx.realtimeCall) {
            campaignDetector.recordCall(ctx.number)
        }
        return null // never blocks
    }
}

// ─────────────────────────────────────────────────────────────────────
// Weaker blocks — temporal, statistical, heuristic, ML
// ─────────────────────────────────────────────────────────────────────

internal class TimeBlockChecker : IChecker {
    override val priority = CheckerPriority.TIME_BLOCK
    override val name = "time_block"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_TIME_BLOCK] ?: false

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val start = (ctx.prefs[SpamRepository.KEY_TIME_BLOCK_START] ?: 22).coerceIn(0, 23)
        val end = (ctx.prefs[SpamRepository.KEY_TIME_BLOCK_END] ?: 7).coerceIn(0, 23)
        if (start == end) return null // same hour = feature disabled

        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val inWindow =
            if (start < end) {
                now in start until end
            } else {
                now >= start || now < end // overnight wrap
            }

        return if (inWindow) {
            BlockResult.block("time_block", "unknown", "Blocked during quiet hours")
        } else {
            null
        }
    }
}

internal class FrequencyEscalationChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.FREQUENCY_ESCALATION
    override val name = "frequency"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_FREQ_ESCALATION] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val windowMs = 7 * 86_400_000L // 7-day window
        val freq = repo.getNumberFrequencySinceInternal(ctx.number, System.currentTimeMillis() - windowMs)
        val threshold = (ctx.prefs[SpamRepository.KEY_FREQ_THRESHOLD] ?: 3).coerceAtLeast(2)
        return if (freq >= threshold) {
            BlockResult.block("frequency", "repeat_caller", "Called $freq times in 7 days - auto-blocked")
        } else {
            null
        }
    }
}

internal class HeuristicChecker(
    private val repo: SpamRepositoryImpl,
    private val appContext: Context,
    private val spamHeuristics: SpamHeuristics,
) : IChecker {
    override val priority = CheckerPriority.HEURISTIC
    override val name = "heuristic"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_HEURISTICS] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val recentBlocked = repo.getRecentBlockedNumbersInternal(System.currentTimeMillis() - 3_600_000L)
        val sms = if (ctx.prefs[SpamRepository.KEY_SMS_CONTENT] ?: true) ctx.smsBody else null

        val hResult =
            spamHeuristics.analyze(
                context = appContext,
                number = ctx.number,
                smsBody = sms,
                recentBlockedNumbers = recentBlocked.map { it.number to it.timestamp },
            )

        val aggressive = ctx.prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false
        val threshold = if (aggressive) 30 else 60

        if (hResult.score >= threshold) {
            return BlockResult.block(
                matchSource = "heuristic",
                type = classifyHeuristicReasons(hResult.reasons),
                description = hResult.reasons.joinToString(", ") { it.replace("_", " ") },
                confidence = hResult.score,
            )
        }

        // Suspicious-but-not-blocked overlay (realtime only, score 30..threshold)
        if (ctx.realtimeCall && hResult.score in 30 until threshold) {
            showCallerIdOverlay(
                appContext,
                ctx.number,
                hResult.score,
                hResult.reasons.firstOrNull() ?: "suspicious",
            )
        }
        return null
    }

    private fun classifyHeuristicReasons(reasons: List<String>): String =
        when {
            "premium_rate" in reasons -> "premium_scam"
            "wangiri_country" in reasons -> "wangiri_scam"
            "neighbor_spoof" in reasons -> "spoofed"
            "rapid_fire" in reasons -> "robocall"
            "spam_keywords" in reasons -> "sms_spam"
            "shortened_url" in reasons || "suspicious_tld" in reasons -> "phishing"
            "voip_spam_range" in reasons -> "robocall"
            else -> "suspicious"
        }

    private fun showCallerIdOverlay(
        ctx: Context,
        number: String,
        confidence: Int,
        reason: String,
    ) {
        try {
            val intent =
                Intent(ctx, CallerIdOverlayService::class.java).apply {
                    putExtra("number", number)
                    putExtra("confidence", confidence)
                    putExtra("reason", reason)
                    putExtra(
                        "verification_status",
                        CallerIdOverlayService.VERIFICATION_STATUS_UNKNOWN,
                    )
                }
            ctx.startService(intent)
        } catch (_: Exception) {
            // overlay is best-effort
        }
    }
}

internal class CampaignBurstChecker(
    private val campaignDetector: CampaignDetector,
) : IChecker {
    override val priority = CheckerPriority.CAMPAIGN_BURST
    override val name = "campaign_burst"

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (campaignDetector.isActiveCampaign(ctx.number)) {
            BlockResult.block(
                matchSource = "campaign_burst",
                type = "robocall",
                description = "Active spam campaign detected from this prefix",
                confidence = 75,
            )
        } else {
            null
        }
}

internal class MlScorerChecker(
    private val spamMLScorer: SpamMLScorer,
) : IChecker {
    override val priority = CheckerPriority.ML_SCORER
    override val name = "ml_scorer"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_ML_SCORER] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val verdict = spamMLScorer.verdict(ctx.number)
        return if (verdict.isSpam) {
            BlockResult.block(
                matchSource = "ml_scorer",
                type = "robocall",
                description = "ML model: ${verdict.confidence}% spam probability",
                confidence = verdict.confidence,
            )
        } else {
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// SMS-only extensions — run after the shared chain passes, only for SMS
// ─────────────────────────────────────────────────────────────────────

/**
 * Named with a trailing `_Checker` to avoid colliding with the top-level
 * `SmsContextChecker` object.
 */
internal class SmsContextChecker_Checker(
    private val appContext: Context,
    private val smsContextChecker: SmsContextChecker,
) : IChecker {
    override val priority = CheckerPriority.PUSH_ALERT_BRIDGE // sits at trust tier
    override val name = "sms_context"

    override suspend fun check(ctx: CheckContext): BlockResult? =
        if (smsContextChecker.isTrustedSender(appContext, ctx.number)) {
            BlockResult.allow("sms_context")
        } else {
            null
        }
}

internal class SmsBurstChecker(
    private val appContext: Context,
    private val smsContextChecker: SmsContextChecker,
) : IChecker {
    override val priority = CheckerPriority.SMS_BURST
    override val name = "sms_burst"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_SMS_BURST] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val signal = smsContextChecker.findRecentBurst(appContext, ctx.number) ?: return null
        return BlockResult.block(
            matchSource = "sms_burst",
            type = "sms_spam",
            description = signal.description,
            confidence = SMS_BURST_CONFIDENCE,
        )
    }

    private companion object {
        const val SMS_BURST_CONFIDENCE = 85
    }
}

internal class SmsKeywordChecker(
    private val repo: SpamRepositoryImpl,
) : IChecker {
    override val priority = CheckerPriority.WILDCARD_RULE - 100
    override val name = "keyword"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val body = ctx.smsBody ?: return null
        val rules = repo.getActiveKeywordsCachedInternal()
        if (rules.isEmpty()) return null
        val now = java.util.Calendar.getInstance()
        for (rule in rules) {
            if (rule.matchesNow(body, now)) {
                return BlockResult.block("keyword", "sms_spam", "Keyword: ${rule.keyword}")
            }
        }
        return null
    }
}

internal class SmsContentChecker(
    private val smsContentAnalyzer: SmsContentAnalyzer,
) : IChecker {
    override val priority = CheckerPriority.ML_SCORER - 100
    override val name = "sms_content"

    override suspend fun isEnabled(ctx: CheckContext): Boolean = ctx.prefs[SpamRepository.KEY_SMS_CONTENT] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val body = ctx.smsBody ?: return null
        val result = smsContentAnalyzer.analyze(body)
        val aggressive = ctx.prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false
        val threshold = if (aggressive) 25 else 50
        return if (result.score >= threshold) {
            BlockResult.block(
                matchSource = "sms_content",
                type = "sms_spam",
                description = result.reasons.joinToString(", ") { it.replace("_", " ") },
                confidence = result.score,
            )
        } else {
            null
        }
    }
}
