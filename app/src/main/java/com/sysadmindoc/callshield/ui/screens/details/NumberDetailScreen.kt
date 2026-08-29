package com.sysadmindoc.callshield.ui.screens.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BlockReasoning
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SmsBodyRedactor
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.data.remote.RemoteLookupStatus
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.friendlyMatchReasonLabel
import com.sysadmindoc.callshield.ui.screens.lookup.SpamScoreGauge
import com.sysadmindoc.callshield.ui.screens.lookup.detectionIcon
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.launchViewUrlSafely
import com.sysadmindoc.callshield.util.localizedDateTimeFormat
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

@Composable
fun NumberDetailScreen(
    number: String,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val numberCalls by
        remember(number) { viewModel.observeBlockedCallsForNumber(number) }
            .collectAsStateWithLifecycle(initialValue = emptyList<BlockedCall>())
    val dbEntry by
        remember(number) { viewModel.observeSpamNumber(number) }
            .collectAsStateWithLifecycle(initialValue = null)
    val userBlocked by viewModel.userBlockedNumbers.collectAsStateWithLifecycle()

    val isBlocked = userBlocked.any { it.number == number }
    val callCount = numberCalls.count { it.isCall }
    val smsCount = numberCalls.count { !it.isCall }
    val firstSeen = numberCalls.minByOrNull { it.timestamp }?.timestamp
    val lastSeen = numberCalls.maxByOrNull { it.timestamp }?.timestamp
    val dateFormat = remember(context) { localizedDateTimeFormat(context, withYear = true) }
    val location = remember(number) { AreaCodeLookup.lookup(number) }
    val areaCode = remember(number) { AreaCodeLookup.getAreaCode(number) }
    val copiedMessage = stringResource(R.string.detail_copied)
    val numberBlockedMessage = stringResource(R.string.detail_number_blocked)
    val numberUnblockedMessage = stringResource(R.string.detail_number_unblocked)
    val clipLabelPhone = stringResource(R.string.clip_label_phone)
    val blockedFromDetail = stringResource(R.string.detail_blocked_from_detail)
    val whitelistedFromDetail = stringResource(R.string.detail_whitelisted_from_detail)
    val blockAreaCodeDescription =
        areaCode?.let { code ->
            if (location != null) {
                stringResource(R.string.detail_block_area_code_description_location, code, location)
            } else {
                stringResource(R.string.detail_block_area_code_description, code)
            }
        }
    val reportIssueTitle = stringResource(R.string.detail_report_issue_title, number)
    val reportIssueBody =
        pluralStringResource(
            R.plurals.detail_report_issue_body,
            numberCalls.size,
            number,
            numberCalls.size,
        )

    // Contact name resolution
    var contactName by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(context.applicationContext, number) {
        contactName =
            withContext(Dispatchers.IO) {
                lookupContactName(context.applicationContext, number)
            }
    }

    // Live spam check result — keyed on number so a deep link that changes the
    // number in place (tapping a notification while another detail is open)
    // doesn't show the previous number's gauge/reputation.
    var liveResult by remember(number) { mutableStateOf<SpamCheckResult?>(null) }
    LaunchedEffect(number) {
        try {
            liveResult = withContext(Dispatchers.IO) { SpamRepository.getInstance(context).isSpam(number, realtimeCall = false) }
        } catch (_: Exception) {
            liveResult = null
        }
    }

    // Multi-source lookup
    var webResult by remember(number) { mutableStateOf<ExternalLookup.MultiLookupResult?>(null) }
    var webLoading by remember(number) { mutableStateOf(false) }
    var webFailed by remember(number) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = CatText)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.detail_number_details),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = CatText,
            )
            // Copy button
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(clipLabelPhone, number))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.cd_copy), tint = CatSubtext) }
        }
        SectionHeader(stringResource(R.string.detail_phone_number), CatSubtext)
        contactName?.let { name ->
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = CatGreen)
        }
        Text(
            PhoneFormatter.formatIsolated(number),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CatText,
        )
        Text(
            location ?: PhoneFormatter.formatWithCountryCodeIsolated(number),
            style = MaterialTheme.typography.bodyLarge,
            color = CatSubtext,
        )

        // Lead with a concise verdict. The score remains secondary evidence.
        liveResult?.let { r ->
            val reasoning =
                remember(r.reasonCode, r.matchSource, r.description, r.confidence) {
                    BlockReasoning.explain(
                        reasonCode = r.reasonCode,
                        matchSource = r.matchSource,
                        description = r.description,
                        confidence = r.confidence,
                    )
                }
            val accent = if (r.isSpam) CatRed else CatGreen
            PremiumCard(accentColor = accent) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (r.isSpam) {
                            SpamScoreGauge(score = r.confidence, isSpam = true)
                        } else {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(72.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(
                                    if (r.isSpam) R.string.detail_high_risk else R.string.detail_no_risk,
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                            if (r.isSpam) {
                                val categoryPolicy =
                                    remember(r.matchSource) {
                                        com.sysadmindoc.callshield.data.CategoryCallPolicy
                                            .parseMatchSource(r.matchSource)
                                    }
                                val sourceLabel =
                                    if (categoryPolicy == null) {
                                        friendlyMatchReasonLabel(r.reasonCode.wireValue)
                                    } else {
                                        stringResource(
                                            R.string.detail_category_action_source,
                                            stringResource(categoryPolicy.category.stringResId),
                                            stringResource(categoryPolicy.action.labelResId),
                                        )
                                    }
                                Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                            }
                            if (isBlocked) {
                                Text(
                                    stringResource(R.string.detail_currently_blocked).uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CatRed,
                                )
                            }
                        }
                    }
                    if (reasoning.bullets.isNotEmpty()) {
                        GradientDivider(color = accent)
                        reasoning.bullets.take(3).forEach { bullet ->
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(bullet, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumActionButton(
                label = stringResource(if (isBlocked) R.string.detail_unblock else R.string.detail_block),
                icon = if (isBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                color = CatRed,
                onClick = {
                    if (isBlocked) {
                        userBlocked.find { it.number == number }?.let { viewModel.unblockNumber(it) }
                        hapticTick(context)
                        Toast.makeText(context, numberUnblockedMessage, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.blockNumber(number, "spam", blockedFromDetail)
                        hapticConfirm(context)
                        Toast.makeText(context, numberBlockedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            PremiumActionButton(
                label = stringResource(R.string.detail_report),
                icon = Icons.Default.Flag,
                color = CatRed,
                onClick = {
                    val title = Uri.encode(reportIssueTitle)
                    val body = Uri.encode(reportIssueBody)
                    context.launchViewUrlSafely("https://github.com/SysAdminDoc/CallShield/issues/new?title=$title&body=$body&labels=spam-report")
                },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.detail_call),
                icon = Icons.Default.Phone,
                color = CatText,
                onClick = {
                    context.startActivitySafely(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                    )
                },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
        }

        // Block area code — confirmed first: a ~7.9M-number rule from a stray
        // tap with zero feedback is exactly what the Dashboard flow fixed in
        // v1.7.26. Same dialog + toast here.
        var showAreaBlockConfirm by rememberSaveable { mutableStateOf(false) }
        if (areaCode != null) {
            PremiumActionButton(
                label = stringResource(R.string.detail_block_area_code, areaCode),
                icon = Icons.Default.FilterAlt,
                color = CatYellow,
                onClick = { showAreaBlockConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            )
        }
        if (showAreaBlockConfirm && areaCode != null) {
            val areaAddedToast = stringResource(R.string.dashboard_block_area_added, areaCode)
            AlertDialog(
                onDismissRequest = { showAreaBlockConfirm = false },
                title = { Text(stringResource(R.string.dashboard_block_area_confirm_title, areaCode)) },
                text = {
                    Text(
                        stringResource(
                            R.string.dashboard_block_area_confirm_body,
                            areaCode,
                            location ?: stringResource(R.string.detail_unknown_location),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.addWildcardRule("+1$areaCode*", false, blockAreaCodeDescription.orEmpty())
                        android.widget.Toast
                            .makeText(context, areaAddedToast, android.widget.Toast.LENGTH_SHORT)
                            .show()
                        showAreaBlockConfirm = false
                    }) {
                        Text(stringResource(R.string.dashboard_block_area_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAreaBlockConfirm = false }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }

        // Stats
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.detail_statistics), color = CatBlue)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(stringResource(R.string.detail_calls), callCount.toString(), CatRed)
                    StatChip(stringResource(R.string.detail_sms), smsCount.toString(), CatMauve)
                    StatChip(stringResource(R.string.detail_total), numberCalls.size.toString(), CatBlue)
                }
                dbEntry?.let { databaseEntry ->
                    Spacer(Modifier.height(12.dp))
                    GradientDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            text = databaseEntry.type.replaceFirstChar { it.uppercase() },
                            color = CatRed,
                            horizontalPadding = 10.dp,
                            verticalPadding = 6.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                        StatusPill(
                            text =
                                pluralStringResource(
                                    R.plurals.detail_reports_count_plural,
                                    databaseEntry.reports,
                                    databaseEntry.reports,
                                ),
                            color = CatPeach,
                            horizontalPadding = 10.dp,
                            verticalPadding = 6.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (databaseEntry.description.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(databaseEntry.description, color = CatSubtext, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Timeline
        if (firstSeen != null) {
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.detail_timeline), color = CatLavender)
                    Spacer(Modifier.height(8.dp))
                    TimelineRow(stringResource(R.string.detail_first_seen), dateFormat.format(Date(firstSeen)))
                    if (lastSeen != null && lastSeen != firstSeen) {
                        TimelineRow(stringResource(R.string.detail_last_seen), dateFormat.format(Date(lastSeen)))
                    }
                    val reasons = numberCalls.map { it.reasonCode }.filterNot { it == com.sysadmindoc.callshield.domain.model.BlockReasonCode.UNKNOWN }.distinct()
                    if (reasons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        GradientDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.detail_match_reasons), style = MaterialTheme.typography.labelMedium, color = CatOverlay)
                        reasons.forEach { reasonCode ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                                Icon(detectionIcon(reasonCode.wireValue), null, tint = CatPeach, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(friendlyMatchReasonLabel(reasonCode.wireValue), style = MaterialTheme.typography.bodySmall, color = CatPeach)
                            }
                        }
                    }
                }
            }
        }

        // Recent activity
        if (numberCalls.isNotEmpty()) {
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.detail_recent_activity), color = CatTeal)
                    Spacer(Modifier.height(8.dp))
                    numberCalls.take(10).forEach { call ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (call.isCall) Icons.Default.Phone else Icons.Default.Sms,
                                null,
                                tint = if (call.isCall) CatRed else CatMauve,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(dateFormat.format(Date(call.timestamp)), style = MaterialTheme.typography.bodySmall, color = CatSubtext, modifier = Modifier.weight(1f))
                            if (call.confidence < 100) Text("${call.confidence}%", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                        }
                        val redactedSmsBody =
                            remember(call.smsBody) {
                                SmsBodyRedactor.redactForPreview(call.smsBody)
                            }
                        if (redactedSmsBody != null) {
                            Text(
                                redactedSmsBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = CatSubtext.copy(alpha = 0.7f),
                                maxLines = 2,
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                    }
                }
            }
        }

        // Multi-source online lookup
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.detail_online_lookup), color = CatBlue)
                    Spacer(Modifier.weight(1f))
                    if (webResult == null) {
                        PremiumActionButton(
                            label = stringResource(R.string.detail_check_sources),
                            icon = Icons.Default.Search,
                            color = CatBlue,
                            onClick = {
                                webLoading = true
                                webFailed = false
                                coroutineScope.launch {
                                    try {
                                        webResult = ExternalLookup.lookupAll(number)
                                    } catch (_: Exception) {
                                        // Offline / DNS failure used to leave the
                                        // card looking like it had never run.
                                        webFailed = true
                                    }
                                    webLoading = false
                                }
                            },
                            enabled = !webLoading,
                            loading = webLoading,
                            outlined = true,
                        )
                    }
                }
                if (webFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.detail_check_sources_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach,
                    )
                }
                if (webLoading) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CatBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.detail_checking_sources),
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.detail_lookup_privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatOverlay,
                )
                webResult?.let { wr ->
                    Spacer(Modifier.height(8.dp))
                    if (wr.totalReports > 0) {
                        val reportCount =
                            pluralStringResource(
                                R.plurals.detail_reports_count_plural,
                                wr.totalReports,
                                wr.totalReports,
                            )
                        val sourceCount =
                            pluralStringResource(
                                R.plurals.detail_sources_count_plural,
                                wr.sources.size,
                                wr.sources.size,
                            )
                        Text(
                            stringResource(R.string.detail_reports_across_sources, reportCount, sourceCount),
                            color = CatRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        val hasDefinitiveSource = wr.sources.any { src -> !src.status.isFallback }
                        Text(
                            stringResource(
                                if (hasDefinitiveSource) {
                                    R.string.detail_clean_all_sources
                                } else {
                                    R.string.detail_no_definitive_source_result
                                },
                            ),
                            color = CatGreen,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    wr.sources.forEach { src ->
                        val isFallback = src.status.isFallback
                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    src.isSpam -> Icons.Default.Warning
                                    isFallback -> Icons.Default.Info
                                    else -> Icons.Default.CheckCircle
                                },
                                null,
                                tint =
                                    when {
                                        src.isSpam -> CatRed
                                        isFallback -> CatSubtext
                                        else -> CatGreen
                                    },
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(src.source, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                            Text(
                                when {
                                    src.reports > 0 -> {
                                        pluralStringResource(
                                            R.plurals.detail_reports_count_label,
                                            src.reports,
                                            src.reports,
                                        )
                                    }

                                    src.isSpam -> {
                                        stringResource(R.string.detail_flagged)
                                    }

                                    else -> {
                                        remoteLookupStatusLabel(src)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = CatSubtext,
                            )
                        }
                    }
                    if (wr.communityNotes.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        wr.communityNotes.take(3).forEach { note ->
                            Text(note, style = MaterialTheme.typography.labelSmall, color = CatOverlay, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Community contribution buttons
        val contributeResult by viewModel.contributeResult.collectAsStateWithLifecycle()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumActionButton(
                label = stringResource(R.string.detail_report_spam),
                icon = Icons.Default.Flag,
                color = CatRed,
                onClick = {
                    hapticTick(context)
                    viewModel.contributeToDatabase(number, dbEntry?.type ?: liveResult?.type ?: "spam")
                },
                modifier = Modifier.weight(1f),
            )
            PremiumActionButton(
                label = stringResource(R.string.detail_not_spam),
                icon = Icons.Default.ThumbUp,
                color = CatGreen,
                onClick = {
                    hapticTick(context)
                    viewModel.reportNotSpam(number)
                },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
        }
        contributeResult?.let {
            Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.success) CatGreen else CatRed)
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(4000)
                viewModel.clearContributeResult()
            }
        }

        // FTC fraud report — copies the number + opens reportfraud.ftc.gov.
        // The FTC form doesn't accept URL params, so we do the next-best
        // thing: clipboard-seed the number and tell the user to paste.
        PremiumActionButton(
            label = stringResource(R.string.detail_ftc_complaint),
            icon = Icons.Default.Gavel,
            color = CatPeach,
            onClick = {
                hapticTick(context)
                com.sysadmindoc.callshield.data.ReportFraudHelper
                    .report(context, number)
            },
            modifier = Modifier.fillMaxWidth(),
            outlined = true,
        )

        // Whitelist and share actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumActionButton(
                label = stringResource(R.string.detail_whitelist),
                icon = Icons.Default.CheckCircle,
                color = CatGreen,
                onClick = { viewModel.addToWhitelist(number, whitelistedFromDetail) },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.detail_share),
                icon = Icons.Default.Share,
                color = CatYellow,
                onClick = {
                    viewModel.shareAsSpam(number, dbEntry?.type ?: liveResult?.type ?: "")
                },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
        }
    }
}

@Composable
private fun remoteLookupStatusLabel(source: ExternalLookup.SourceResult): String =
    stringResource(
        remoteLookupStatusStringRes(
            status = source.status,
            hasDetail = source.detail.isNotBlank(),
        ),
    )

private fun remoteLookupStatusStringRes(
    status: RemoteLookupStatus,
    hasDetail: Boolean,
): Int =
    when (status) {
        RemoteLookupStatus.FOUND -> {
            if (hasDetail) {
                R.string.remote_lookup_status_caller_id_found
            } else {
                R.string.remote_lookup_status_found
            }
        }

        RemoteLookupStatus.CLEAN -> {
            R.string.remote_lookup_status_clean
        }

        RemoteLookupStatus.DISABLED -> {
            R.string.remote_lookup_status_disabled
        }

        RemoteLookupStatus.INVALID_INPUT -> {
            R.string.remote_lookup_status_invalid_input
        }

        RemoteLookupStatus.TIMEOUT -> {
            R.string.remote_lookup_status_timeout
        }

        RemoteLookupStatus.RATE_LIMITED -> {
            R.string.remote_lookup_status_rate_limited
        }

        RemoteLookupStatus.HTTP_ERROR -> {
            R.string.remote_lookup_status_http_error
        }

        RemoteLookupStatus.EMPTY_BODY -> {
            R.string.remote_lookup_status_empty_body
        }

        RemoteLookupStatus.BODY_TOO_LARGE -> {
            R.string.remote_lookup_status_body_too_large
        }

        RemoteLookupStatus.UNREADABLE_BODY -> {
            R.string.remote_lookup_status_unreadable_body
        }

        RemoteLookupStatus.PARSE_ERROR -> {
            R.string.remote_lookup_status_parse_error
        }

        RemoteLookupStatus.UNAVAILABLE -> {
            R.string.remote_lookup_status_unavailable
        }
    }

@Composable
fun StatChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.sp), fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
    }
}

@Composable
fun TimelineRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CatOverlay, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

private fun lookupContactName(
    context: Context,
    number: String,
): String? =
    try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }
