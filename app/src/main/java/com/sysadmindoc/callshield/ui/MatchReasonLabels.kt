package com.sysadmindoc.callshield.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.R

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

/**
 * Localized display label for an internal matchReason/matchSource token.
 *
 * Every screen that shows a block reason must use this instead of the old
 * cosmetic `replace("_", " ")` — that leaked internal tokens ("hot list",
 * "db match", "sms content") to users and was untranslatable. Extracted from
 * StatsScreen so Dashboard, Blocked Log, Recent, Lookup, and Number Detail
 * render the same words Stats does.
 */
@Composable
fun friendlyMatchReasonLabel(reason: String): String =
    when {
        reason.contains("database", ignoreCase = true) -> stringResource(R.string.stats_reason_spam_database)
        reason.contains("hot_list", ignoreCase = true) -> stringResource(R.string.stats_reason_hot_list)
        reason.contains("hot_campaign", ignoreCase = true) -> stringResource(R.string.stats_reason_live_campaign)
        reason.contains("heuristic", ignoreCase = true) -> stringResource(R.string.stats_reason_heuristic)
        reason.contains("sms_content", ignoreCase = true) -> stringResource(R.string.stats_reason_sms_content)
        reason.contains("spam_domain", ignoreCase = true) -> stringResource(R.string.stats_reason_spam_domain)
        reason.contains("ml_scorer", ignoreCase = true) -> stringResource(R.string.stats_reason_ml_scorer)
        reason.contains("rcs_", ignoreCase = true) -> stringResource(R.string.stats_reason_rcs_filter)
        reason.contains("stir", ignoreCase = true) -> stringResource(R.string.stats_reason_stir_shaken)
        reason.contains("prefix", ignoreCase = true) -> stringResource(R.string.stats_reason_prefix_match)
        reason.contains("wildcard", ignoreCase = true) -> stringResource(R.string.stats_reason_wildcard_rule)
        reason.contains("keyword", ignoreCase = true) -> stringResource(R.string.stats_reason_keyword_rule)
        reason.contains("frequency", ignoreCase = true) -> stringResource(R.string.stats_reason_repeat_caller)
        reason.contains("time", ignoreCase = true) -> stringResource(R.string.stats_reason_quiet_hours)
        reason.contains("user", ignoreCase = true) -> stringResource(R.string.stats_reason_manual_block)
        reason.isBlank() || reason == "unknown" -> stringResource(R.string.stats_reason_unknown)
        else -> reason.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
