package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.domain.model.BlockReasonCode

/**
 * Generates a plain-English explanation of why a given block fired.
 *
 * Built from the stable `BlockReasonCode`, optional structured match source,
 * description, and confidence. Legacy match text is accepted only at the
 * compatibility boundary; decision branches switch on the enum.
 *
 * The goal is trust-building for the #1 false-positive complaint pattern
 * (*"CallShield blocked my bank — why?"*). A clear narrative lets the
 * user understand the decision and either confirm it's spam, whitelist
 * the number, or report a false positive.
 */
object BlockReasoning {
    private val probabilisticReasons =
        setOf(
            BlockReasonCode.HEURISTIC,
            BlockReasonCode.CAMPAIGN_BURST,
            BlockReasonCode.ML_SCORER,
            BlockReasonCode.SMS_CONTENT,
        )

    data class Reasoning(
        /** One-line summary shown at the top of the panel. */
        val headline: String,
        /** Ordered bullet points with the decision details. */
        val bullets: List<String>,
    )

    /**
     * Whether a confidence value represents an actual probabilistic signal.
     *
     * Most protection decisions are deterministic rule matches. Their stored
     * confidence is an implementation score or a legacy 100 value, not a
     * probability that should be presented to a person as one.
     */
    fun isProbabilistic(reasonCode: BlockReasonCode): Boolean = reasonCode in probabilisticReasons

    /** A personal block is reversible by removing the user's saved rule. */
    fun isUserRule(reasonCode: BlockReasonCode): Boolean = reasonCode == BlockReasonCode.USER_BLOCKLIST || reasonCode == BlockReasonCode.TEMPORARY_BLOCK

    /**
     * @param matchReason from `BlockedCall.matchReason` or `SpamCheckResult.matchSource`
     *   (e.g. `user_blocklist`, `database`, `prefix`, `wildcard`, `time_block`,
     *   `frequency`, `heuristic`, `campaign_burst`, `ml_scorer`, `keyword`,
     *   `sms_content`, `rcs_*` derivatives, `emergency_contact`, `manual_whitelist`).
     * @param description from `BlockedCall.description` — for heuristics and
     *   content analysis this is a comma-separated list of reasons like
     *   "high_spam_npa, voip_spam_range, neighbor_spoof".
     * @param confidence 0-100 score (only meaningful for heuristic, ML,
     *   campaign_burst, sms_content layers).
     */
    fun explain(
        matchReason: String,
        description: String,
        confidence: Int,
    ): Reasoning =
        explain(
            reasonCode = BlockReasonCode.fromMatchSource(matchReason),
            description = description,
            confidence = confidence,
            matchSource = matchReason,
            preserveUnknownSourceForLegacyTest = true,
        )

    /** Explain a decision using its stable code; matchSource is only retained for structured metadata. */
    @Suppress("CyclomaticComplexMethod")
    fun explain(
        reasonCode: BlockReasonCode,
        description: String,
        confidence: Int,
        matchSource: String? = null,
        preserveUnknownSourceForLegacyTest: Boolean = false,
    ): Reasoning {
        CategoryCallPolicy.parseMatchSource(matchSource.orEmpty())?.let { policy ->
            return explainCategoryPolicy(policy, description, confidence)
        }
        val rcsMatchSource = matchSource.orEmpty()
        val bullets = mutableListOf<String>()
        val headline: String

        when {
            reasonCode == BlockReasonCode.USER_BLOCKLIST -> {
                headline = "You blocked this number."
                bullets += "Matched your personal blocklist at detection layer 5."
                if (description.isNotBlank()) bullets += "Note: \"$description\""
            }

            reasonCode == BlockReasonCode.DATABASE -> {
                headline = "This number is in CallShield's community spam database."
                bullets += "Matched at detection layer 6 (database lookup)."
                if (description.isNotBlank()) bullets += "Type on file: $description"
            }

            reasonCode == BlockReasonCode.DB_PREFIX_EXPANSION -> {
                headline = "This number matches a known spam database prefix."
                bullets += "Matched at detection layer 6 (database prefix expansion)."
                if (description.isNotBlank()) bullets += "Prefix tag: $description"
            }

            reasonCode == BlockReasonCode.HOT_LIST -> {
                headline = "This number is on CallShield's active spam hot list."
                bullets += "Matched the short-lived hot-list layer for recently reported activity."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.PREFIX -> {
                headline = "This number's prefix is a known spam range."
                bullets += "Matched at detection layer 7 (prefix rules — premium-rate / wangiri country codes)."
                if (description.isNotBlank()) bullets += "Prefix tag: $description"
            }

            reasonCode == BlockReasonCode.WILDCARD -> {
                headline = "This number matched one of your wildcard / regex rules."
                bullets += "Matched at detection layer 8 (wildcard rules)."
                if (description.isNotBlank()) bullets += "Rule: $description"
            }

            reasonCode == BlockReasonCode.TIME_BLOCK -> {
                headline = "Blocked during your quiet hours."
                bullets += "Matched at detection layer 9 (quiet-hours time window)."
                bullets += "Your contacts and whitelisted numbers still ring through during quiet hours."
            }

            reasonCode == BlockReasonCode.FREQUENCY -> {
                headline = "This number has called you too often."
                bullets += "Matched at detection layer 10 (frequency auto-block — 3+ calls)."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.HEURISTIC -> {
                headline = "Flagged by the heuristic engine at $confidence% confidence."
                bullets += "Matched at detection layer 11 (heuristics)."
                description.split(",").map { it.trim().replace("_", " ") }.filter { it.isNotBlank() }.forEach {
                    bullets += "• $it"
                }
            }

            reasonCode == BlockReasonCode.CAMPAIGN_BURST -> {
                headline = "This prefix is running an active spam campaign."
                bullets += "Matched at detection layer 11.5 (campaign burst detector)."
                bullets += "5+ distinct numbers from this NPA-NXX prefix have called in the last hour."
                if (description.isNotBlank()) bullets += description
                bullets += "Campaign confidence: $confidence%."
            }

            reasonCode == BlockReasonCode.ML_SCORER -> {
                headline = "The on-device ML model flagged this number as $confidence% likely spam."
                bullets += "Matched at detection layer 15 (gradient-boosted tree spam scorer)."
                bullets += "The model runs entirely on your device — no data sent anywhere."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.KEYWORD -> {
                headline = "The SMS matched one of your keyword rules."
                bullets += "Matched at detection layer 13 (SMS keyword rules)."
                if (description.isNotBlank()) bullets += "Rule: $description"
            }

            reasonCode == BlockReasonCode.SPAM_DOMAIN -> {
                headline = "The message included a known spam domain."
                bullets += "Matched at the spam-domain protection layer."
                if (description.isNotBlank()) bullets += "Domain signal: $description"
            }

            reasonCode == BlockReasonCode.SMS_CONTENT -> {
                headline = "The SMS content looked like spam ($confidence% confidence)."
                bullets += "Matched at detection layer 14 (SMS content analysis)."
                description.split(",").map { it.trim().replace("_", " ") }.filter { it.isNotBlank() }.forEach {
                    bullets += "• $it"
                }
            }

            reasonCode == BlockReasonCode.SMS_BURST -> {
                headline = "This sender matched SMS burst protection."
                bullets += "Multiple unknown SMS arrived from this sender or prefix in a short window."
                bullets += "Use the notification actions to mark the sender safe or report the burst."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.RCS_FILTER -> {
                val inner = rcsMatchSource.removePrefix("rcs_").ifBlank { "filtered content" }
                headline = "RCS message blocked via notification filter."
                bullets += "Matched via the RCS Filter (NotificationListener bridge)."
                bullets += "Underlying reason: $inner."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.STIR_SHAKEN_FAILED -> {
                val display =
                    StirShakenSemantics.forAndroidVerificationStatus(
                        StirShakenSemantics.VERIFICATION_STATUS_FAILED,
                    )
                headline = display?.headline ?: "Carrier caller ID authentication failed."
                bullets += display?.bullets.orEmpty()
            }

            reasonCode == BlockReasonCode.STIR_SHAKEN_TRUSTED -> {
                val display =
                    StirShakenSemantics.forAndroidVerificationStatus(
                        StirShakenSemantics.VERIFICATION_STATUS_PASSED,
                    )
                headline = display?.headline ?: "Carrier caller ID authentication passed."
                bullets += display?.bullets.orEmpty()
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.HIDDEN_NUMBER -> {
                headline = "Call came in with no phone number attached."
                bullets += "Blocked by your \"block unknown numbers\" setting."
            }

            // Allow-through sources (shown only on NumberDetail for allowed calls)
            reasonCode == BlockReasonCode.EMERGENCY_CONTACT -> {
                headline = "This is one of your emergency contacts."
                bullets += "Always rings through — bypasses blocklist, quiet hours, and aggressive mode."
            }

            reasonCode == BlockReasonCode.MANUAL_WHITELIST -> {
                headline = "You added this number to your whitelist."
                bullets += "Always allowed — matched layer 1 (manual whitelist)."
            }

            reasonCode == BlockReasonCode.CONTACT_WHITELIST -> {
                headline = "This number is in your phone's contacts."
                bullets += "Always allowed — matched layer 2 (contact whitelist)."
            }

            reasonCode == BlockReasonCode.RECENTLY_DIALED -> {
                headline = "You called this number recently, so we let the callback through."
                bullets += "Matched layer 3 (callback detection) — any number you've dialed in the last 24h rings through even if it's in a spam database."
            }

            reasonCode == BlockReasonCode.ANSWERED_CALLER -> {
                headline = "You've answered this caller repeatedly."
                bullets +=
                    "Matched answered-caller trust — this number has recent answered-call history on this device."
                bullets += "Explicit blocklist, wildcard, range, STIR-failed, and system block rules still win first."
            }

            reasonCode == BlockReasonCode.EMERGENCY_CALLBACK -> {
                headline = "Emergency callback grace is active."
                bullets += "A local emergency call was placed recently, so unknown callbacks can ring through."
                bullets += "Explicit blocklist, wildcard, range, STIR-failed, and system block rules still win first."
            }

            reasonCode == BlockReasonCode.REPEATED_URGENT -> {
                headline = "Likely urgent — same number called twice in under 5 minutes."
                bullets += "Matched layer 4 (repeated-urgent-caller allow-through)."
                bullets += "Robocallers don't usually retry immediately; humans with an emergency do."
            }

            reasonCode == BlockReasonCode.SMS_CONTEXT -> {
                headline = "You've had a real conversation with this number."
                bullets += "Matched SMS context trust — you've sent a message to this number, or received from it on 2+ distinct days."
            }

            reasonCode == BlockReasonCode.PIPELINE_DIAGNOSTIC -> {
                headline = "Protection ran in degraded mode for this decision."
                bullets += "CallShield allowed the activity while one or more detection stages were not evaluated."
                if (description.isNotBlank()) bullets += description
            }

            reasonCode == BlockReasonCode.UNKNOWN && rcsMatchSource.isBlank() -> {
                headline = "No block — this number was allowed through."
                bullets += "None of the 15+ detection layers matched."
            }

            else -> {
                headline =
                    if (preserveUnknownSourceForLegacyTest && reasonCode == BlockReasonCode.UNKNOWN) {
                        "Blocked at layer: $rcsMatchSource"
                    } else {
                        "Blocked by an unrecognized protection rule."
                    }
                if (description.isNotBlank()) bullets += description
                if (confidence in 1..99) bullets += "Confidence: $confidence%."
            }
        }

        return Reasoning(headline = headline, bullets = bullets)
    }

    private fun explainCategoryPolicy(
        policy: CategoryPolicyMatch,
        description: String,
        confidence: Int,
    ): Reasoning {
        val category = policy.category.policyDisplayName()
        val headline =
            when (policy.action) {
                CategoryCallAction.ALLOW -> "$category calls are allowed by your category rule."
                CategoryCallAction.SILENCE -> "$category calls are sent silently to voicemail by your category rule."
                CategoryCallAction.BLOCK -> "$category calls are blocked by your category rule."
                CategoryCallAction.INHERIT -> error("Inherited actions are never encoded as policy decisions")
            }
        val underlying =
            explain(
                reasonCode = BlockReasonCode.fromMatchSource(policy.originalMatchSource),
                description = description,
                confidence = confidence,
                matchSource = policy.originalMatchSource,
            )
        return Reasoning(
            headline = headline,
            bullets =
                listOf(
                    "Underlying detection: ${underlying.headline}",
                    "Emergency and manual whitelists, plus explicit personal block rules, take precedence.",
                ) + underlying.bullets,
        )
    }

    private fun CallCategory.policyDisplayName(): String =
        when (this) {
            CallCategory.DebtCollector -> "Debt collector"
            CallCategory.Political -> "Political"
            CallCategory.Robocall -> "Robocall"
            CallCategory.Scam -> "Scam"
            CallCategory.Phishing -> "Phishing"
            CallCategory.Telemarketer -> "Telemarketer"
            CallCategory.Wangiri -> "Wangiri"
            CallCategory.Survey -> "Survey"
            CallCategory.Business -> "Business"
            CallCategory.Unknown -> "Unknown"
        }
}
