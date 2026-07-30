package com.sysadmindoc.callshield.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.R

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
