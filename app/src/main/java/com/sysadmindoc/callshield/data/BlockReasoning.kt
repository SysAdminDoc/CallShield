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
        return explainAllow(reasonCode, description)
            ?: explainPersonalRule(reasonCode, description)
            ?: explainReputation(reasonCode, description, confidence)
            ?: explainMessage(reasonCode, description, confidence, matchSource.orEmpty())
            ?: explainOther(reasonCode, description, confidence, matchSource.orEmpty(), preserveUnknownSourceForLegacyTest)
    }

    /** Safety floors and every layer that lets a call or message through. */
    private fun explainAllow(
        reasonCode: BlockReasonCode,
        description: String,
    ): Reasoning? =
        when (reasonCode) {
            BlockReasonCode.EMERGENCY_FLOOR -> {
                reasoning(
                    "Emergency and public-safety numbers always ring.",
                    "No rule, list or setting in CallShield can block them.",
                )
            }

            BlockReasonCode.OTP_FLOOR -> {
                reasoning(
                    "This looked like a verification code, so it was let through.",
                    "Short messages carrying a one-time code are never blocked, so sign-ins keep working.",
                )
            }

            BlockReasonCode.EMERGENCY_CONTACT -> {
                reasoning(
                    "This is one of your emergency contacts.",
                    "Always rings through. It bypasses your blocklist, quiet hours and aggressive mode.",
                )
            }

            BlockReasonCode.MANUAL_WHITELIST -> {
                reasoning("You added this number to your whitelist.", "Whitelisted numbers always ring.")
            }

            BlockReasonCode.CONTACT_WHITELIST -> {
                reasoning("This number is in your phone's contacts.", "Contacts always ring.")
            }

            BlockReasonCode.TEMPORARY_ALLOW -> {
                reasoning(
                    "You allowed this number for a while.",
                    "It rings until the temporary allow runs out.",
                    "Your blocklist, wildcard and range rules still win.",
                )
            }

            BlockReasonCode.STIR_SHAKEN_TRUSTED -> {
                val display = StirShakenSemantics.forAndroidVerificationStatus(StirShakenSemantics.VERIFICATION_STATUS_PASSED)
                Reasoning(
                    headline = display?.headline ?: "Carrier caller ID authentication passed.",
                    bullets = display?.bullets.orEmpty() + listOfNotNull(description.takeIf { it.isNotBlank() }),
                )
            }

            BlockReasonCode.REGULATORY_ALLOW -> {
                reasoning(
                    "This number is in a series the regulator protects.",
                    "Banks, insurers and government offices call from it, so it rings through past the spam database, the heuristics, quiet hours and region rules.",
                    "Your blocklist, wildcard and range rules still win, and so do numbers you blocked in Android.",
                )
            }

            BlockReasonCode.RECENTLY_DIALED -> {
                reasoning(
                    "You called this number recently, so the callback rang through.",
                    "Any number you called in the last 24 hours rings through, even when it's in the spam database.",
                )
            }

            BlockReasonCode.EMERGENCY_CALLBACK -> {
                reasoning(
                    "Emergency callback grace is active.",
                    "A local emergency call was placed recently, so unknown callbacks can ring through.",
                    EXPLICIT_BLOCKS_STILL_WIN,
                )
            }

            BlockReasonCode.ANSWERED_CALLER -> {
                reasoning(
                    "You've answered this caller repeatedly.",
                    "This number has recent answered calls on this phone.",
                    EXPLICIT_BLOCKS_STILL_WIN,
                )
            }

            BlockReasonCode.REPEATED_URGENT -> {
                reasoning(
                    "Likely urgent. The same number called twice in under five minutes.",
                    "Robocallers rarely retry right away. People with an emergency do.",
                )
            }

            BlockReasonCode.CALLER_NAME_TRUST -> {
                reasoning(
                    "The caller's name matched one of your trusted names.",
                    "The name comes from your carrier's caller ID. It gets a caller past region rules, quiet hours and the spam heuristics.",
                    "The spam database and your own block rules still win.",
                )
            }

            BlockReasonCode.PUSH_ALERT -> {
                reasoning(
                    "An app you use said this call was coming.",
                    "A recent notification from a delivery, ride or messaging app mentioned this number or an arriving driver.",
                    "The spam database and your own block rules still win.",
                )
            }

            BlockReasonCode.SMS_CONTEXT -> {
                reasoning(
                    "You've had a real conversation with this number.",
                    "You've sent it a message, or it has written to you on two or more different days.",
                )
            }

            else -> {
                null
            }
        }

    /** Blocks and silences that come from a rule or setting the user chose. */
    private fun explainPersonalRule(
        reasonCode: BlockReasonCode,
        description: String,
    ): Reasoning? =
        when (reasonCode) {
            BlockReasonCode.USER_BLOCKLIST -> {
                reasoning("You blocked this number.", "It's on your personal blocklist.", description.labeled("Note: \"", "\""))
            }

            BlockReasonCode.TEMPORARY_BLOCK -> {
                reasoning("You blocked this number for a while.", "The block ends when its timer runs out.")
            }

            BlockReasonCode.SYSTEM_BLOCK_LIST -> {
                reasoning(
                    "This number is on your phone's blocked-numbers list.",
                    "CallShield follows the numbers you block in your phone or messages app.",
                )
            }

            BlockReasonCode.CONTACTS_ONLY -> {
                reasoning(
                    "Blocked because contacts-only mode is on.",
                    "Only your contacts and numbers you've allowed can ring while it's on.",
                )
            }

            BlockReasonCode.WILDCARD -> {
                reasoning("This number matched one of your wildcard or regex rules.", description.labeled("Rule: "))
            }

            BlockReasonCode.HASH_WILDCARD -> {
                reasoning("This number matched one of your range patterns.", description.labeled("Pattern: "))
            }

            BlockReasonCode.REGULATORY_PREFIX -> {
                reasoning(
                    "This number is in a telemarketing range you chose to block.",
                    "Some regulators make sales calls come from a set number range, so they're easy to tell apart.",
                    description.labeled("Range: "),
                )
            }

            BlockReasonCode.REGION_BLOCK -> {
                reasoning(
                    "This number is outside the regions you allow.",
                    description.takeIf { it.isNotBlank() },
                    "Region rules are in Settings under Region & caller-name rules.",
                )
            }

            BlockReasonCode.TIME_BLOCK -> {
                reasoning(
                    "Blocked during your quiet hours.",
                    "Your contacts and whitelisted numbers still ring through during quiet hours.",
                )
            }

            BlockReasonCode.MEETING_MODE -> {
                reasoning(
                    "Silenced while you were in a meeting.",
                    "Meeting mode sends calls from outside your contacts to voicemail while a meeting app you picked shows a call in progress.",
                    description.labeled("Meeting app: "),
                    "The call wasn't marked as spam, and Android still lists it as missed.",
                )
            }

            BlockReasonCode.CALLER_NAME_BLOCK -> {
                reasoning(
                    "The caller's name matched one of your blocked names.",
                    description.takeIf { it.isNotBlank() },
                    "Every allow rule, contacts included, still wins.",
                )
            }

            BlockReasonCode.HIDDEN_NUMBER -> {
                reasoning("Call came in with no phone number attached.", "Blocked by your \"block unknown numbers\" setting.")
            }

            else -> {
                null
            }
        }

    /** Downloaded reputation data, carrier verdicts and statistical layers. */
    private fun explainReputation(
        reasonCode: BlockReasonCode,
        description: String,
        confidence: Int,
    ): Reasoning? =
        when (reasonCode) {
            BlockReasonCode.DATABASE -> {
                reasoning("This number is in CallShield's community spam database.", description.labeled("Type on file: "))
            }

            BlockReasonCode.DB_PREFIX_EXPANSION -> {
                reasoning(
                    "This number sits next to confirmed spam numbers.",
                    "It shares all but its last two digits with numbers in the spam database, and database prefix expansion is on.",
                    description.labeled("Prefix tag: "),
                )
            }

            BlockReasonCode.HOT_LIST -> {
                reasoning(
                    "This number is on CallShield's active spam hot list.",
                    "Recent reports put it on the short-lived trending list.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            BlockReasonCode.PREFIX -> {
                reasoning(
                    "This number's prefix is a known spam range.",
                    "It matched the downloaded list of premium-rate and callback-scam prefixes.",
                    description.labeled("Prefix tag: "),
                )
            }

            BlockReasonCode.STIR_SHAKEN_FAILED -> {
                val display = StirShakenSemantics.forAndroidVerificationStatus(StirShakenSemantics.VERIFICATION_STATUS_FAILED)
                Reasoning(
                    headline = display?.headline ?: "Carrier caller ID authentication failed.",
                    bullets = display?.bullets.orEmpty(),
                )
            }

            BlockReasonCode.FREQUENCY -> {
                reasoning(
                    "This number has called you too often.",
                    "It called more times in a week than your repeat-caller limit allows.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            BlockReasonCode.HEURISTIC -> {
                Reasoning(
                    headline = "Flagged by the heuristic engine at $confidence% confidence.",
                    bullets = listOf("Signals that fired:") + listed(description),
                )
            }

            BlockReasonCode.CAMPAIGN_BURST -> {
                reasoning(
                    "This prefix is running an active spam campaign.",
                    "5+ distinct numbers from this NPA-NXX prefix have called in the last hour.",
                    description.takeIf { it.isNotBlank() },
                    "Campaign confidence: $confidence%.",
                )
            }

            BlockReasonCode.CAMPAIGN_RECORDER -> {
                reasoning(
                    "Counted toward spam-wave detection.",
                    "This step only records the call. It never blocks or allows one on its own.",
                )
            }

            BlockReasonCode.ML_SCORER -> {
                reasoning(
                    "The on-device ML model flagged this number as $confidence% likely spam.",
                    "The gradient-boosted tree model runs entirely on your device, and nothing is sent anywhere.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            else -> {
                null
            }
        }

    /** Text and notification screening. */
    private fun explainMessage(
        reasonCode: BlockReasonCode,
        description: String,
        confidence: Int,
        matchSource: String,
    ): Reasoning? =
        when (reasonCode) {
            BlockReasonCode.KEYWORD -> {
                reasoning("The SMS matched one of your keyword rules.", description.labeled("Rule: "))
            }

            BlockReasonCode.SPAM_DOMAIN -> {
                reasoning("The message included a known spam domain.", description.labeled("Domain signal: "))
            }

            BlockReasonCode.SMS_CONTENT -> {
                Reasoning(
                    headline = "The SMS content looked like spam ($confidence% confidence).",
                    bullets = listOf("Signals that fired:") + listed(description),
                )
            }

            BlockReasonCode.SMS_BURST -> {
                reasoning(
                    "This sender matched SMS burst protection.",
                    "Multiple unknown SMS arrived from this sender or prefix in a short window.",
                    "Use the notification actions to mark the sender safe or report the burst.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            BlockReasonCode.RCS_FILTER -> {
                reasoning(
                    "RCS message blocked via notification filter.",
                    "Matched through the notification filter for RCS messages.",
                    "Underlying reason: ${matchSource.removePrefix("rcs_").ifBlank { "filtered content" }}.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            else -> {
                null
            }
        }

    private fun explainOther(
        reasonCode: BlockReasonCode,
        description: String,
        confidence: Int,
        matchSource: String,
        preserveUnknownSourceForLegacyTest: Boolean,
    ): Reasoning =
        when {
            reasonCode == BlockReasonCode.PIPELINE_DIAGNOSTIC -> {
                reasoning(
                    "Protection ran in degraded mode for this decision.",
                    "CallShield allowed the activity while one or more detection stages were not evaluated.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            reasonCode == BlockReasonCode.CATEGORY_POLICY -> {
                reasoning(
                    "Handled by one of your category rules.",
                    "You chose how calls in this category are handled in Settings.",
                    description.takeIf { it.isNotBlank() },
                )
            }

            reasonCode == BlockReasonCode.UNKNOWN && matchSource.isBlank() -> {
                reasoning("No block. This number was allowed through.", "None of CallShield's checks matched it.")
            }

            else -> {
                reasoning(
                    if (preserveUnknownSourceForLegacyTest && reasonCode == BlockReasonCode.UNKNOWN) {
                        "Blocked at layer: $matchSource"
                    } else {
                        UNRECOGNIZED_HEADLINE
                    },
                    description.takeIf { it.isNotBlank() },
                    "Confidence: $confidence%.".takeIf { confidence in 1..99 },
                )
            }
        }

    private fun reasoning(
        headline: String,
        vararg bullets: String?,
    ): Reasoning = Reasoning(headline = headline, bullets = bullets.filterNotNull())

    private fun String.labeled(
        prefix: String,
        suffix: String = "",
    ): String? = takeIf { it.isNotBlank() }?.let { "$prefix$it$suffix" }

    private fun listed(description: String): List<String> =
        description
            .split(",")
            .map { it.trim().replace("_", " ") }
            .filter { it.isNotBlank() }
            .map { "• $it" }

    internal const val UNRECOGNIZED_HEADLINE = "Blocked by an unrecognized protection rule."

    private const val EXPLICIT_BLOCKS_STILL_WIN =
        "Explicit blocklist, wildcard, range, STIR-failed, and system block rules still win first."

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
