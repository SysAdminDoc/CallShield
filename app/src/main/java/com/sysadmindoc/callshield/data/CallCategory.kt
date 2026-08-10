package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

/**
 * A human-friendly label for why a call was blocked or flagged.
 *
 * Resolved from `SpamCheckResult` (matchSource + type + description +
 * confidence) via [CallCategoryResolver]. Designed to answer the question
 * a real user actually asks: **not** "what detection layer fired?" but
 * "what is this?".
 *
 * Trust rule: we only emit a specific category when the underlying
 * evidence is strong (high-confidence heuristics, unambiguous database
 * tags, unambiguous regex patterns). Anything else falls back to
 * [Unknown]. Mislabeling a bank as Scam destroys trust faster than a
 * missing label does.
 */
enum class CallCategory(
    val stringResId: Int,
    val emoji: String,
    val storageKey: String,
) {
    DebtCollector(
        com.sysadmindoc.callshield.R.string.call_category_debt_collector,
        "\uD83D\uDCB3",
        "debt_collector",
    ), // 💳
    Political(com.sysadmindoc.callshield.R.string.call_category_political, "\uD83D\uDDF3", "political"), // 🗳
    Robocall(com.sysadmindoc.callshield.R.string.call_category_robocall, "\uD83E\uDD16", "robocall"), // 🤖
    Scam(com.sysadmindoc.callshield.R.string.call_category_scam, "\u26A0\uFE0F", "scam"), // ⚠
    Phishing(com.sysadmindoc.callshield.R.string.call_category_phishing, "\uD83C\uDFA3", "phishing"), // 🎣
    Telemarketer(com.sysadmindoc.callshield.R.string.call_category_telemarketer, "\uD83D\uDCE2", "telemarketer"), // 📢
    Wangiri(com.sysadmindoc.callshield.R.string.call_category_wangiri, "\uD83C\uDF0D", "wangiri"), // 🌍
    Survey(com.sysadmindoc.callshield.R.string.call_category_survey, "\uD83D\uDCCB", "survey"), // 📋
    Business(com.sysadmindoc.callshield.R.string.call_category_business, "\uD83C\uDFE2", "business"), // 🏢
    Unknown(com.sysadmindoc.callshield.R.string.call_category_unknown, "\u2753", "unknown"), // ❓
}

object CallCategoryResolver {
    /** Convenience overload for callers holding a persisted `BlockedCall`
     *  (which doesn't carry a SpamCheckResult type field but does carry
     *  matchReason, description, and confidence). */
    fun resolveFromLog(
        matchReason: String,
        type: String,
        description: String,
        confidence: Int,
    ): CallCategory {
        if (matchReason.isBlank()) return CallCategory.Unknown
        val synthetic =
            SpamCheckResult(
                isSpam = true,
                matchSource = matchReason,
                type = type,
                description = description,
                confidence = confidence,
            )
        return resolve(synthetic)
    }

    /**
     * Map a SpamCheckResult to a user-facing category.
     *
     * We inspect, in order:
     *   1. Explicit `type` fields from the spam database (most trustworthy
     *      — these come from FCC/FTC/community tagging).
     *   2. The `matchSource` / layer that fired (e.g. `ml_scorer`,
     *      `campaign_burst`, `time_block`).
     *   3. Keyword fingerprints in the `description` (e.g. "wangiri",
     *      "premium_rate") with conservative thresholds.
     *   4. Fallback to `Unknown` when evidence isn't strong enough to
     *      avoid mis-labeling legitimate callers.
     */
    @Suppress("ReturnCount")
    fun resolve(result: SpamCheckResult): CallCategory {
        if (!result.isSpam) return CallCategory.Unknown
        CategoryCallPolicy.parseMatchSource(result.matchSource)?.let { return it.category }

        // (1) Database type tags are authoritative — they came from
        //     labeled community/FCC/FTC data, not heuristics.
        when (result.type.lowercase().trim()) {
            "debt_collector", "debt" -> return CallCategory.DebtCollector
            "political", "robo_political" -> return CallCategory.Political
            "phishing", "smishing" -> return CallCategory.Phishing
            "scam", "premium_scam", "financial_scam", "tech_support_scam" -> return CallCategory.Scam
            "wangiri", "wangiri_scam" -> return CallCategory.Wangiri
            "survey" -> return CallCategory.Survey
            "telemarketer", "telemarketing", "sales" -> return CallCategory.Telemarketer
            "robocall", "recorded_message" -> return CallCategory.Robocall
            "sms_spam" -> return CallCategory.Phishing // SMS spam is overwhelmingly phishing
        }

        // (2) Layer-based inference for numbers we don't have a database
        //     tag for (heuristics, ML, campaign burst, prefix rules).
        val desc = result.description.lowercase()

        // Wangiri / premium-rate prefix rules are extremely specific.
        if (result.reasonCode == BlockReasonCode.PREFIX &&
            (desc.contains("wangiri") || desc.contains("premium"))
        ) {
            return if (desc.contains("wangiri")) CallCategory.Wangiri else CallCategory.Scam
        }

        // SMS content analysis pattern → phishing.
        if (result.reasonCode == BlockReasonCode.SMS_CONTENT ||
            result.reasonCode == BlockReasonCode.KEYWORD ||
            result.reasonCode == BlockReasonCode.RCS_FILTER
        ) {
            return CallCategory.Phishing
        }

        // Campaign burst = coordinated robocall wave, by definition.
        if (result.reasonCode == BlockReasonCode.CAMPAIGN_BURST) return CallCategory.Robocall

        // Heuristic reasons: map the strongest signals.
        if (result.reasonCode == BlockReasonCode.HEURISTIC) {
            when {
                "wangiri" in desc -> return CallCategory.Wangiri
                "premium" in desc -> return CallCategory.Scam
                "neighbor_spoof" in desc -> return CallCategory.Scam
                "rapid_fire" in desc -> return CallCategory.Robocall
                "voip_spam_range" in desc -> return CallCategory.Robocall
                "hot_campaign_range" in desc -> return CallCategory.Robocall
                "toll_free" in desc -> return CallCategory.Telemarketer
            }
        }

        // High-confidence ML hits default to Robocall — the GBT model is
        // trained on patterns that overwhelmingly correlate with automated
        // dialers.
        if (result.reasonCode == BlockReasonCode.ML_SCORER && result.confidence >= 80) {
            return CallCategory.Robocall
        }

        return CallCategory.Unknown
    }
}
