package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.annotation.StringRes
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.ui.pipelineCheckerLabelRes

/**
 * Generates a plain-language explanation of why a given block fired, in the
 * app language.
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
     *   content analysis this is a list of signal labels joined with the app
     *   language's separator (older rows hold raw tokens like "voip_spam_range").
     * @param confidence 0-100 score (only meaningful for heuristic, ML,
     *   campaign_burst, sms_content layers).
     */
    fun explain(
        context: Context,
        matchReason: String,
        description: String,
        confidence: Int,
    ): Reasoning =
        explain(
            context = context,
            reasonCode = BlockReasonCode.fromMatchSource(matchReason),
            description = description,
            confidence = confidence,
            matchSource = matchReason,
            preserveUnknownSourceForLegacyTest = true,
        )

    /** Explain a decision using its stable code; matchSource is only retained for structured metadata. */
    fun explain(
        context: Context,
        reasonCode: BlockReasonCode,
        description: String,
        confidence: Int,
        matchSource: String? = null,
        preserveUnknownSourceForLegacyTest: Boolean = false,
    ): Reasoning = Explainer(context).explain(reasonCode, description, confidence, matchSource, preserveUnknownSourceForLegacyTest)

    /** Every sentence comes from string resources so the panel follows the app language. */
    private class Explainer(
        private val context: Context,
    ) {
        private fun s(
            @StringRes id: Int,
        ): String = context.getString(id)

        private fun s(
            @StringRes id: Int,
            arg: Any,
        ): String = context.getString(id, arg)

        fun explain(
            reasonCode: BlockReasonCode,
            description: String,
            confidence: Int,
            matchSource: String?,
            preserveUnknownSourceForLegacyTest: Boolean,
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
                    reasoning(s(R.string.reasoning_emergency_floor_headline), s(R.string.reasoning_emergency_floor_detail))
                }

                BlockReasonCode.OTP_FLOOR -> {
                    reasoning(s(R.string.reasoning_otp_floor_headline), s(R.string.reasoning_otp_floor_detail))
                }

                BlockReasonCode.EMERGENCY_CONTACT -> {
                    reasoning(s(R.string.reasoning_emergency_contact_headline), s(R.string.reasoning_emergency_contact_detail))
                }

                BlockReasonCode.MANUAL_WHITELIST -> {
                    reasoning(s(R.string.reasoning_manual_whitelist_headline), s(R.string.reasoning_manual_whitelist_detail))
                }

                BlockReasonCode.CONTACT_WHITELIST -> {
                    reasoning(s(R.string.reasoning_contact_whitelist_headline), s(R.string.reasoning_contact_whitelist_detail))
                }

                BlockReasonCode.TEMPORARY_ALLOW -> {
                    reasoning(
                        s(R.string.reasoning_temporary_allow_headline),
                        s(R.string.reasoning_temporary_allow_detail),
                        s(R.string.reasoning_still_win_temporary_allow),
                    )
                }

                BlockReasonCode.STIR_SHAKEN_TRUSTED -> {
                    val display = StirShakenSemantics.forAndroidVerificationStatus(context, StirShakenSemantics.VERIFICATION_STATUS_PASSED)
                    Reasoning(
                        headline = display?.headline ?: s(R.string.stir_passed_headline),
                        bullets = display?.bullets.orEmpty() + listOfNotNull(description.takeIf { it.isNotBlank() }),
                    )
                }

                BlockReasonCode.REGULATORY_ALLOW -> {
                    reasoning(
                        s(R.string.reasoning_regulatory_allow_headline),
                        s(R.string.reasoning_regulatory_allow_detail),
                        s(R.string.reasoning_still_win_protected_series),
                    )
                }

                BlockReasonCode.RECENTLY_DIALED -> {
                    trustSignal(R.string.reasoning_recently_dialed_headline, R.string.reasoning_recently_dialed_detail)
                }

                BlockReasonCode.EMERGENCY_CALLBACK -> {
                    trustSignal(R.string.reasoning_emergency_callback_headline, R.string.reasoning_emergency_callback_detail)
                }

                BlockReasonCode.ANSWERED_CALLER -> {
                    trustSignal(R.string.reasoning_answered_caller_headline, R.string.reasoning_answered_caller_detail)
                }

                BlockReasonCode.REPEATED_URGENT -> {
                    trustSignal(R.string.reasoning_repeated_urgent_headline, R.string.reasoning_repeated_urgent_detail)
                }

                BlockReasonCode.CALLER_NAME_TRUST -> {
                    trustSignal(R.string.reasoning_caller_name_trust_headline, R.string.reasoning_caller_name_trust_detail)
                }

                BlockReasonCode.PUSH_ALERT -> {
                    trustSignal(R.string.reasoning_push_alert_headline, R.string.reasoning_push_alert_detail)
                }

                BlockReasonCode.SMS_CONTEXT -> {
                    reasoning(
                        s(R.string.reasoning_sms_context_headline),
                        s(R.string.reasoning_sms_context_detail),
                        s(R.string.reasoning_sms_context_limit),
                    )
                }

                else -> {
                    null
                }
            }

        // What still outranks an allow, by band of the CheckerPriority ladder.
        // BlockReasoningTest checks each sentence against the priorities.
        private fun trustSignal(
            @StringRes headline: Int,
            @StringRes detail: Int,
        ): Reasoning = reasoning(s(headline), s(detail), s(R.string.reasoning_still_win_trust_signals))

        /** Blocks and silences that come from a rule or setting the user chose. */
        private fun explainPersonalRule(
            reasonCode: BlockReasonCode,
            description: String,
        ): Reasoning? =
            when (reasonCode) {
                BlockReasonCode.USER_BLOCKLIST -> {
                    reasoning(
                        s(R.string.reasoning_user_blocklist_headline),
                        s(R.string.reasoning_user_blocklist_detail),
                        description.labeled(R.string.reasoning_note),
                    )
                }

                BlockReasonCode.TEMPORARY_BLOCK -> {
                    reasoning(s(R.string.reasoning_temporary_block_headline), s(R.string.reasoning_temporary_block_detail))
                }

                BlockReasonCode.SYSTEM_BLOCK_LIST -> {
                    reasoning(s(R.string.reasoning_system_block_list_headline), s(R.string.reasoning_system_block_list_detail))
                }

                BlockReasonCode.CONTACTS_ONLY -> {
                    reasoning(s(R.string.reasoning_contacts_only_headline), s(R.string.reasoning_contacts_only_detail))
                }

                BlockReasonCode.WILDCARD -> {
                    reasoning(s(R.string.reasoning_wildcard_headline), description.labeled(R.string.reasoning_rule))
                }

                BlockReasonCode.HASH_WILDCARD -> {
                    reasoning(s(R.string.reasoning_hash_wildcard_headline), description.labeled(R.string.reasoning_pattern))
                }

                BlockReasonCode.REGULATORY_PREFIX -> {
                    reasoning(
                        s(R.string.reasoning_regulatory_prefix_headline),
                        s(R.string.reasoning_regulatory_prefix_detail),
                        description.labeled(R.string.reasoning_range),
                    )
                }

                BlockReasonCode.REGION_BLOCK -> {
                    reasoning(
                        s(R.string.reasoning_region_block_headline),
                        description.takeIf { it.isNotBlank() },
                        s(R.string.reasoning_region_block_where),
                    )
                }

                BlockReasonCode.TIME_BLOCK -> {
                    reasoning(s(R.string.reasoning_time_block_headline), s(R.string.reasoning_time_block_detail))
                }

                BlockReasonCode.MEETING_MODE -> {
                    reasoning(
                        s(R.string.reasoning_meeting_mode_headline),
                        s(R.string.reasoning_meeting_mode_detail),
                        description.labeled(R.string.reasoning_meeting_app),
                        s(R.string.reasoning_meeting_mode_not_spam),
                    )
                }

                BlockReasonCode.CALLER_NAME_BLOCK -> {
                    reasoning(
                        s(R.string.reasoning_caller_name_block_headline),
                        description.takeIf { it.isNotBlank() },
                        s(R.string.reasoning_caller_name_block_detail),
                    )
                }

                BlockReasonCode.HIDDEN_NUMBER -> {
                    reasoning(s(R.string.reasoning_hidden_number_headline), s(R.string.reasoning_hidden_number_detail))
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
                    reasoning(s(R.string.reasoning_database_headline), description.labeled(R.string.reasoning_type_on_file))
                }

                BlockReasonCode.DB_PREFIX_EXPANSION -> {
                    reasoning(
                        s(R.string.reasoning_db_prefix_headline),
                        s(R.string.reasoning_db_prefix_detail),
                        description.labeled(R.string.reasoning_prefix_tag),
                    )
                }

                BlockReasonCode.HOT_LIST -> {
                    reasoning(
                        s(R.string.reasoning_hot_list_headline),
                        s(R.string.reasoning_hot_list_detail),
                        description.takeIf { it.isNotBlank() },
                    )
                }

                BlockReasonCode.PREFIX -> {
                    reasoning(
                        s(R.string.reasoning_prefix_headline),
                        s(R.string.reasoning_prefix_detail),
                        description.labeled(R.string.reasoning_prefix_tag),
                    )
                }

                BlockReasonCode.STIR_SHAKEN_FAILED -> {
                    val display = StirShakenSemantics.forAndroidVerificationStatus(context, StirShakenSemantics.VERIFICATION_STATUS_FAILED)
                    Reasoning(
                        headline = display?.headline ?: s(R.string.stir_failed_headline),
                        bullets = display?.bullets.orEmpty(),
                    )
                }

                BlockReasonCode.FREQUENCY -> {
                    reasoning(
                        s(R.string.reasoning_frequency_headline),
                        s(R.string.reasoning_frequency_detail),
                        description.takeIf { it.isNotBlank() },
                    )
                }

                BlockReasonCode.HEURISTIC -> {
                    Reasoning(
                        headline = s(R.string.reasoning_heuristic_headline, confidence),
                        bullets = listOf(s(R.string.reasoning_signals_fired)) + listed(description),
                    )
                }

                BlockReasonCode.CAMPAIGN_BURST -> {
                    reasoning(
                        s(R.string.reasoning_campaign_headline),
                        s(R.string.reasoning_campaign_detail),
                        description.takeIf { it.isNotBlank() },
                        s(R.string.reasoning_campaign_confidence, confidence),
                    )
                }

                BlockReasonCode.CAMPAIGN_RECORDER -> {
                    reasoning(s(R.string.reasoning_campaign_recorder_headline), s(R.string.reasoning_campaign_recorder_detail))
                }

                BlockReasonCode.ML_SCORER -> {
                    reasoning(
                        s(R.string.reasoning_ml_headline, confidence),
                        s(R.string.reasoning_ml_detail),
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
                    reasoning(s(R.string.reasoning_keyword_headline), description.labeled(R.string.reasoning_rule))
                }

                BlockReasonCode.SPAM_DOMAIN -> {
                    reasoning(s(R.string.reasoning_spam_domain_headline), description.labeled(R.string.reasoning_domain_signal))
                }

                BlockReasonCode.SMS_CONTENT -> {
                    Reasoning(
                        headline = s(R.string.reasoning_sms_content_headline, confidence),
                        bullets = listOf(s(R.string.reasoning_signals_fired)) + listed(description),
                    )
                }

                BlockReasonCode.SMS_BURST -> {
                    reasoning(
                        s(R.string.reasoning_sms_burst_headline),
                        s(R.string.reasoning_sms_burst_detail),
                        s(R.string.reasoning_sms_burst_action),
                        description.takeIf { it.isNotBlank() },
                    )
                }

                BlockReasonCode.RCS_FILTER -> {
                    // The underlying check is named the way the block log names
                    // it, never by its internal id.
                    val underlying =
                        matchSource
                            .removePrefix("rcs_")
                            .takeIf { it.isNotBlank() }
                            ?.let { s(pipelineCheckerLabelRes(it)) }
                            ?: s(R.string.reasoning_rcs_filtered_content)
                    reasoning(
                        s(R.string.reasoning_rcs_headline),
                        s(R.string.reasoning_rcs_detail),
                        s(R.string.reasoning_rcs_underlying, underlying),
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
                        s(R.string.reasoning_pipeline_diagnostic_headline),
                        s(R.string.reasoning_pipeline_diagnostic_detail),
                        description.takeIf { it.isNotBlank() },
                    )
                }

                reasonCode == BlockReasonCode.CATEGORY_POLICY -> {
                    reasoning(
                        s(R.string.reasoning_category_policy_headline),
                        s(R.string.reasoning_category_policy_detail),
                        description.takeIf { it.isNotBlank() },
                    )
                }

                reasonCode == BlockReasonCode.UNKNOWN && matchSource.isBlank() -> {
                    reasoning(s(R.string.reasoning_allowed_headline), s(R.string.reasoning_allowed_detail))
                }

                else -> {
                    reasoning(
                        if (preserveUnknownSourceForLegacyTest && reasonCode == BlockReasonCode.UNKNOWN) {
                            s(R.string.reasoning_unknown_layer, matchSource)
                        } else {
                            s(R.string.reasoning_unrecognized)
                        },
                        description.takeIf { it.isNotBlank() },
                        s(R.string.reasoning_confidence, confidence).takeIf { confidence in 1..99 },
                    )
                }
            }

        private fun explainCategoryPolicy(
            policy: CategoryPolicyMatch,
            description: String,
            confidence: Int,
        ): Reasoning {
            val category = s(policy.category.stringResId)
            val headline =
                when (policy.action) {
                    CategoryCallAction.ALLOW -> s(R.string.reasoning_category_allowed, category)
                    CategoryCallAction.SILENCE -> s(R.string.reasoning_category_silenced, category)
                    CategoryCallAction.BLOCK -> s(R.string.reasoning_category_blocked, category)
                    CategoryCallAction.INHERIT -> error("Inherited actions are never encoded as policy decisions")
                }
            val underlying =
                explain(
                    reasonCode = BlockReasonCode.fromMatchSource(policy.originalMatchSource),
                    description = description,
                    confidence = confidence,
                    matchSource = policy.originalMatchSource,
                    preserveUnknownSourceForLegacyTest = false,
                )
            return Reasoning(
                headline = headline,
                bullets =
                    listOf(
                        s(R.string.reasoning_underlying_detection, underlying.headline),
                        s(R.string.reasoning_category_precedence),
                    ) + underlying.bullets,
            )
        }

        private fun reasoning(
            headline: String,
            vararg bullets: String?,
        ): Reasoning = Reasoning(headline = headline, bullets = bullets.filterNotNull())

        private fun String.labeled(
            @StringRes format: Int,
        ): String? = takeIf { it.isNotBlank() }?.let { s(format, it) }

        // Signal lists are joined with the app language's separator, so a
        // Chinese row uses "、" (or a full-width comma) rather than ",".
        private fun listed(description: String): List<String> =
            description
                .split(',', '，', '、')
                .map { it.trim().replace("_", " ") }
                .filter { it.isNotBlank() }
                .map { "• $it" }
    }
}
