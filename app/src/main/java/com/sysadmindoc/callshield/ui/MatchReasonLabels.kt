package com.sysadmindoc.callshield.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.domain.model.BlockReasonCode

/**
 * Resource id for a spam `type` token, or null when it is not one we know.
 *
 * Non-composable so non-UI callers (share text, notifications) can localize
 * the same way the screens do instead of falling back to `replace("_", " ")`,
 * which leaks internal tokens like "debt collector" / "ai voice" verbatim.
 */
fun spamTypeLabelRes(type: String): Int? =
    when (type.trim().lowercase()) {
        "spam" -> R.string.spam_type_spam
        "robocall" -> R.string.spam_type_robocall
        "scam" -> R.string.spam_type_scam
        "telemarketer" -> R.string.spam_type_telemarketer
        "debt_collector" -> R.string.spam_type_debt_collector
        "sms_spam" -> R.string.spam_type_sms_spam
        "ai_voice" -> R.string.spam_type_ai_voice
        "survey" -> R.string.spam_type_survey
        "political" -> R.string.spam_type_political
        "phishing" -> R.string.spam_type_phishing
        "spoofed" -> R.string.spam_type_spoofed
        "user_blocked" -> R.string.spam_type_user_blocked
        "caller_name" -> R.string.spam_type_caller_name
        "out_of_region" -> R.string.spam_type_out_of_region
        "premium_scam" -> R.string.spam_type_premium_scam
        "wangiri_scam" -> R.string.spam_type_wangiri_scam
        "suspicious" -> R.string.spam_type_suspicious
        "unknown" -> R.string.spam_type_unknown
        else -> null
    }

/** Localized display label for a spam `type` token. */
fun localizedSpamType(
    context: android.content.Context,
    type: String,
): String =
    spamTypeLabelRes(type)
        ?.let(context::getString)
        ?: context.getString(R.string.spam_type_other)

@Composable
fun friendlySpamTypeLabel(type: String): String =
    spamTypeLabelRes(type)
        ?.let { stringResource(it) }
        ?: stringResource(R.string.spam_type_other)

/** Resource id for a stable, user-facing pipeline checker label. */
fun pipelineCheckerLabelRes(checkerName: String): Int =
    when (checkerName.trim().lowercase()) {
        "emergency_floor" -> R.string.lookup_checker_emergency_floor
        "otp_floor" -> R.string.lookup_checker_otp_floor
        "manual_whitelist" -> R.string.lookup_checker_manual_whitelist
        "contact_whitelist" -> R.string.lookup_checker_contact_whitelist
        "contacts_only" -> R.string.lookup_checker_contacts_only
        "stir_shaken_trusted" -> R.string.lookup_checker_stir_shaken_trusted
        "stir_shaken_failed" -> R.string.lookup_checker_stir_shaken_failed
        "temporary_allow" -> R.string.lookup_checker_temporary_allow
        "system_block_list" -> R.string.lookup_checker_system_block_list
        "user_blocklist" -> R.string.lookup_checker_user_blocklist
        "database" -> R.string.lookup_checker_database
        "db_prefix_expansion" -> R.string.lookup_checker_db_prefix_expansion
        "prefix" -> R.string.lookup_checker_prefix
        "wildcard" -> R.string.lookup_checker_wildcard
        "hash_wildcard" -> R.string.lookup_checker_hash_wildcard
        "recently_dialed" -> R.string.lookup_checker_recently_dialed
        "answered_caller" -> R.string.lookup_checker_answered_caller
        "emergency_callback" -> R.string.lookup_checker_emergency_callback
        "repeated_urgent" -> R.string.lookup_checker_repeated_urgent
        "caller_name_trust" -> R.string.lookup_checker_caller_name_trust
        "caller_name" -> R.string.lookup_checker_caller_name
        "region_block" -> R.string.lookup_checker_region_block
        "campaign_recorder" -> R.string.lookup_checker_campaign_recorder
        "time_block" -> R.string.lookup_checker_time_block
        "frequency" -> R.string.lookup_checker_frequency
        "heuristic" -> R.string.lookup_checker_heuristic
        "campaign_burst" -> R.string.lookup_checker_campaign_burst
        "ml_scorer" -> R.string.lookup_checker_ml_scorer
        "sms_context" -> R.string.lookup_checker_sms_context
        "sms_burst" -> R.string.lookup_checker_sms_burst
        "keyword" -> R.string.lookup_checker_keyword
        "sms_content" -> R.string.lookup_checker_sms_content
        "push_alert" -> R.string.lookup_checker_push_alert
        else -> R.string.lookup_checker_other
    }

/** Localized label that never exposes checker implementation identifiers. */
@Composable
fun friendlyPipelineCheckerLabel(checkerName: String): String = stringResource(pipelineCheckerLabelRes(checkerName))

/** Stable localized label mapping used by every log/statistics surface. */
fun reasonCodeLabelRes(reasonCode: BlockReasonCode): Int =
    when (reasonCode) {
        BlockReasonCode.EMERGENCY_FLOOR -> R.string.stats_reason_emergency_floor
        BlockReasonCode.OTP_FLOOR -> R.string.stats_reason_otp_floor
        BlockReasonCode.DATABASE -> R.string.stats_reason_spam_database
        BlockReasonCode.DB_PREFIX_EXPANSION -> R.string.stats_reason_spam_database
        BlockReasonCode.HOT_LIST -> R.string.stats_reason_hot_list
        BlockReasonCode.CAMPAIGN_BURST -> R.string.stats_reason_live_campaign
        BlockReasonCode.HEURISTIC -> R.string.stats_reason_heuristic
        BlockReasonCode.SMS_CONTENT -> R.string.stats_reason_sms_content
        BlockReasonCode.SPAM_DOMAIN -> R.string.stats_reason_spam_domain
        BlockReasonCode.ML_SCORER -> R.string.stats_reason_ml_scorer
        BlockReasonCode.RCS_FILTER -> R.string.stats_reason_rcs_filter
        BlockReasonCode.STIR_SHAKEN_FAILED, BlockReasonCode.STIR_SHAKEN_TRUSTED -> R.string.stats_reason_stir_shaken
        BlockReasonCode.PREFIX, BlockReasonCode.REGION_BLOCK -> R.string.stats_reason_prefix_match
        BlockReasonCode.WILDCARD, BlockReasonCode.HASH_WILDCARD -> R.string.stats_reason_wildcard_rule
        BlockReasonCode.KEYWORD -> R.string.stats_reason_keyword_rule
        BlockReasonCode.FREQUENCY -> R.string.stats_reason_repeat_caller
        BlockReasonCode.TIME_BLOCK -> R.string.stats_reason_quiet_hours
        BlockReasonCode.USER_BLOCKLIST, BlockReasonCode.TEMPORARY_BLOCK -> R.string.stats_reason_manual_block
        BlockReasonCode.CATEGORY_POLICY -> R.string.stats_reason_category_policy
        BlockReasonCode.HIDDEN_NUMBER -> R.string.stats_reason_hidden_number
        BlockReasonCode.UNKNOWN -> R.string.stats_reason_unknown
        else -> pipelineCheckerLabelRes(reasonCode.wireValue)
    }

/**
 * Localized display label for a stored reason. Legacy rows are converted by
 * [BlockReasonCode.fromStored] at this boundary; no UI surface parses a
 * free-form reason string or exposes an internal token.
 */
@Composable
fun friendlyMatchReasonLabel(reason: String): String = stringResource(reasonCodeLabelRes(BlockReasonCode.fromStored(reason)))
