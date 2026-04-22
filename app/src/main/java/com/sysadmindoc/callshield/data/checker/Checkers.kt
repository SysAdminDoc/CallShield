package com.sysadmindoc.callshield.data.checker

import android.content.Context
import android.content.Intent
import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CampaignDetector
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SmsContextChecker
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.SystemBlockList
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
internal class WhitelistChecker(private val repo: SpamRepository) : IChecker {
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
internal class ContactWhitelistChecker(private val appContext: Context) : IChecker {
    override val priority = CheckerPriority.CONTACT_WHITELIST
    override val name = "contact_whitelist"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_CONTACT_WHITELIST] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (SpamHeuristics.isInContacts(appContext, ctx.number)) {
            BlockResult.allow("contact_whitelist")
        } else null
    }
}

// ─────────────────────────────────────────────────────────────────────
// Block-side: explicit user / DB / rule matches
// ─────────────────────────────────────────────────────────────────────

/**
 * Read-only view of Android's system-wide block list (A4). Allows
 * CallShield to respect blocks set by the stock Phone / Messages app
 * without maintaining a bidirectional mirror. Silently no-ops when the
 * app lacks the default-dialer role.
 */
internal class SystemBlockListChecker(private val appContext: Context) : IChecker {
    override val priority = CheckerPriority.SYSTEM_BLOCK_LIST
    override val name = "system_block_list"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (SystemBlockList.isBlocked(appContext, ctx.number)) {
            BlockResult.block(
                matchSource = "system_block_list",
                type = "user_blocked",
                description = "Blocked via system block list"
            )
        } else null
    }
}

/**
 * Combined user-blocklist + GitHub database lookup — both live in the
 * same `spam_numbers` table. A single Room query handles both.
 */
internal class DatabaseChecker(private val repo: SpamRepository) : IChecker {
    override val priority = CheckerPriority.USER_BLOCKLIST
    override val name = "database"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val entry = repo.findByNumberInternal(ctx.number) ?: return null
        val source = if (entry.isUserBlocked) "user_blocklist" else "database"
        return BlockResult.block(source, entry.type, entry.description)
    }
}

/**
 * NPA-NXX (or arbitrary digit-prefix) matcher. Prefixes are loaded once
 * and cached in [SpamRepository]; cache invalidation happens on sync.
 */
internal class PrefixChecker(private val repo: SpamRepository) : IChecker {
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
internal class WildcardChecker(private val repo: SpamRepository) : IChecker {
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
internal class HashWildcardChecker(private val repo: SpamRepository) : IChecker {
    override val priority = CheckerPriority.HASH_WILDCARD_RULE
    override val name = "hash_wildcard"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val rules = repo.getActiveHashWildcardsCachedInternal()
        if (rules.isEmpty()) return null
        val now = java.util.Calendar.getInstance()
        for (rule in rules) {
            if (rule.matchesNow(ctx.number, now)) {
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

internal class RecentlyDialedChecker(private val appContext: Context) : IChecker {
    override val priority = CheckerPriority.RECENTLY_DIALED
    override val name = "recently_dialed"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (CallbackDetector.wasRecentlyDialed(appContext, ctx.number)) {
            BlockResult.allow("recently_dialed")
        } else null
    }
}

internal class RepeatedUrgentChecker(private val appContext: Context) : IChecker {
    override val priority = CheckerPriority.REPEATED_URGENT
    override val name = "repeated_urgent"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (CallbackDetector.isRepeatedUrgentCall(appContext, ctx.number)) {
            BlockResult.allow("repeated_urgent")
        } else null
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
internal class CampaignRecorderChecker : IChecker {
    override val priority = CheckerPriority.CAMPAIGN_RECORDER
    override val name = "campaign_recorder"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        if (ctx.realtimeCall) {
            CampaignDetector.recordCall(ctx.number)
        }
        return null  // never blocks
    }
}

// ─────────────────────────────────────────────────────────────────────
// Weaker blocks — temporal, statistical, heuristic, ML
// ─────────────────────────────────────────────────────────────────────

internal class TimeBlockChecker : IChecker {
    override val priority = CheckerPriority.TIME_BLOCK
    override val name = "time_block"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_TIME_BLOCK] ?: false

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val start = (ctx.prefs[SpamRepository.KEY_TIME_BLOCK_START] ?: 22).coerceIn(0, 23)
        val end = (ctx.prefs[SpamRepository.KEY_TIME_BLOCK_END] ?: 7).coerceIn(0, 23)
        if (start == end) return null  // same hour = feature disabled

        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val inWindow = if (start < end) now in start until end
                       else now >= start || now < end   // overnight wrap

        return if (inWindow) {
            BlockResult.block("time_block", "unknown", "Blocked during quiet hours")
        } else null
    }
}

internal class FrequencyEscalationChecker(private val repo: SpamRepository) : IChecker {
    override val priority = CheckerPriority.FREQUENCY_ESCALATION
    override val name = "frequency"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_FREQ_ESCALATION] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val windowMs = 7 * 86_400_000L  // 7-day window
        val freq = repo.getNumberFrequencySinceInternal(ctx.number, System.currentTimeMillis() - windowMs)
        val threshold = (ctx.prefs[SpamRepository.KEY_FREQ_THRESHOLD] ?: 3).coerceAtLeast(2)
        return if (freq >= threshold) {
            BlockResult.block("frequency", "repeat_caller", "Called $freq times in 7 days - auto-blocked")
        } else null
    }
}

internal class HeuristicChecker(
    private val repo: SpamRepository,
    private val appContext: Context,
) : IChecker {
    override val priority = CheckerPriority.HEURISTIC
    override val name = "heuristic"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_HEURISTICS] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val recentBlocked = repo.getRecentBlockedNumbersInternal(System.currentTimeMillis() - 3_600_000L)
        val sms = if (ctx.prefs[SpamRepository.KEY_SMS_CONTENT] ?: true) ctx.smsBody else null

        val hResult = SpamHeuristics.analyze(
            context = appContext,
            number = ctx.number,
            smsBody = sms,
            recentBlockedNumbers = recentBlocked.map { it.number to it.timestamp }
        )

        val aggressive = ctx.prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false
        val threshold = if (aggressive) 30 else 60

        if (hResult.score >= threshold) {
            return BlockResult.block(
                matchSource = "heuristic",
                type = classifyHeuristicReasons(hResult.reasons),
                description = hResult.reasons.joinToString(", ") { it.replace("_", " ") },
                confidence = hResult.score
            )
        }

        // Suspicious-but-not-blocked overlay (realtime only, score 30..threshold)
        if (ctx.realtimeCall && hResult.score in 30 until threshold) {
            showCallerIdOverlay(
                appContext, ctx.number, hResult.score,
                hResult.reasons.firstOrNull() ?: "suspicious"
            )
        }
        return null
    }

    private fun classifyHeuristicReasons(reasons: List<String>): String = when {
        "premium_rate" in reasons -> "premium_scam"
        "wangiri_country" in reasons -> "wangiri_scam"
        "neighbor_spoof" in reasons -> "spoofed"
        "rapid_fire" in reasons -> "robocall"
        "spam_keywords" in reasons -> "sms_spam"
        "shortened_url" in reasons || "suspicious_tld" in reasons -> "phishing"
        "voip_spam_range" in reasons -> "robocall"
        else -> "suspicious"
    }

    private fun showCallerIdOverlay(ctx: Context, number: String, confidence: Int, reason: String) {
        try {
            val intent = Intent(ctx, CallerIdOverlayService::class.java).apply {
                putExtra("number", number)
                putExtra("confidence", confidence)
                putExtra("reason", reason)
                putExtra(
                    "verification_status",
                    CallerIdOverlayService.VERIFICATION_STATUS_UNKNOWN
                )
            }
            ctx.startService(intent)
        } catch (_: Exception) { /* overlay is best-effort */ }
    }
}

internal class CampaignBurstChecker : IChecker {
    override val priority = CheckerPriority.CAMPAIGN_BURST
    override val name = "campaign_burst"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (CampaignDetector.isActiveCampaign(ctx.number)) {
            BlockResult.block(
                matchSource = "campaign_burst",
                type = "robocall",
                description = "Active spam campaign detected from this prefix",
                confidence = 75
            )
        } else null
    }
}

internal class MlScorerChecker : IChecker {
    override val priority = CheckerPriority.ML_SCORER
    override val name = "ml_scorer"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_ML_SCORER] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val verdict = SpamMLScorer.verdict(ctx.number)
        return if (verdict.isSpam) {
            BlockResult.block(
                matchSource = "ml_scorer",
                type = "robocall",
                description = "ML model: ${verdict.confidence}% spam probability",
                confidence = verdict.confidence
            )
        } else null
    }
}

// ─────────────────────────────────────────────────────────────────────
// SMS-only extensions — run after the shared chain passes, only for SMS
// ─────────────────────────────────────────────────────────────────────

/**
 * Named with a trailing `_Checker` to avoid colliding with the top-level
 * `SmsContextChecker` object.
 */
internal class SmsContextChecker_Checker(private val appContext: Context) : IChecker {
    override val priority = CheckerPriority.PUSH_ALERT_BRIDGE  // sits at trust tier
    override val name = "sms_context"

    override suspend fun check(ctx: CheckContext): BlockResult? {
        return if (SmsContextChecker.isTrustedSender(appContext, ctx.number)) {
            BlockResult.allow("sms_context")
        } else null
    }
}

internal class SmsKeywordChecker(private val repo: SpamRepository) : IChecker {
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

internal class SmsContentChecker : IChecker {
    override val priority = CheckerPriority.ML_SCORER - 100
    override val name = "sms_content"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_SMS_CONTENT] ?: true

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val body = ctx.smsBody ?: return null
        val result = SmsContentAnalyzer.analyze(body)
        val aggressive = ctx.prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false
        val threshold = if (aggressive) 25 else 50
        return if (result.score >= threshold) {
            BlockResult.block(
                matchSource = "sms_content",
                type = "sms_spam",
                description = result.reasons.joinToString(", ") { it.replace("_", " ") },
                confidence = result.score
            )
        } else null
    }
}
