package com.sysadmindoc.callshield.ui.screens.details

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamCheckResult
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.lookup.SpamScoreGauge
import com.sysadmindoc.callshield.ui.screens.lookup.detectionIcon
import com.sysadmindoc.callshield.ui.theme.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NumberDetailScreen(number: String, viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    val allSpam by viewModel.allSpamNumbers.collectAsState()
    val userBlocked by viewModel.userBlockedNumbers.collectAsState()

    val numberCalls = blockedCalls.filter { it.number == number }
    val dbEntry = allSpam.find { it.number == number }
    val isBlocked = userBlocked.any { it.number == number }
    val callCount = numberCalls.count { it.isCall }
    val smsCount = numberCalls.count { !it.isCall }
    val firstSeen = numberCalls.minByOrNull { it.timestamp }?.timestamp
    val lastSeen = numberCalls.maxByOrNull { it.timestamp }?.timestamp
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }
    val location = remember(number) { AreaCodeLookup.lookup(number) }
    val areaCode = remember(number) { AreaCodeLookup.getAreaCode(number) }

    // Contact name resolution
    var contactName by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(context.applicationContext, number) {
        contactName = withContext(Dispatchers.IO) {
            lookupContactName(context.applicationContext, number)
        }
    }

    // Live spam check result
    var liveResult by remember { mutableStateOf<SpamCheckResult?>(null) }
    LaunchedEffect(number) {
        try {
            liveResult = withContext(Dispatchers.IO) { SpamRepository.getInstance(context).isSpam(number, realtimeCall = false) }
        } catch (_: Exception) {
            liveResult = null
        }
    }

    // Multi-source lookup
    var webResult by remember { mutableStateOf<ExternalLookup.MultiLookupResult?>(null) }
    var webLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = CatSubtext)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                contactName?.let { name ->
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CatGreen)
                }
                Text(PhoneFormatter.format(number), style = if (contactName != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(PhoneFormatter.formatWithCountryCode(number), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                // Feature A: smart call label chip under the header — shows
                // the resolved category (Scam / Debt Collector / Phishing /
                // etc.) once the live spam check completes. Only shown for
                // spam matches; allow-through results don't need a label.
                liveResult?.takeIf { it.isSpam }?.let { r ->
                    val category = remember(r.matchSource, r.type, r.description, r.confidence) {
                        com.sysadmindoc.callshield.data.CallCategoryResolver.resolve(r)
                    }
                    Spacer(Modifier.height(6.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "${category.emoji} ${stringResource(category.stringResId)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = CatRed.copy(alpha = 0.18f),
                            labelColor = CatRed,
                        ),
                    )
                }
            }
            // Copy button
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Phone", number))
                Toast.makeText(context, context.getString(R.string.detail_copied), Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.cd_copy), tint = CatSubtext) }
        }

        // Spam score gauge (live check)
        liveResult?.let { r ->
            PremiumCard(accentColor = if (r.isSpam) CatRed else CatGreen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .accentGlow(
                            color = if (r.isSpam) CatRed else CatGreen,
                            radius = 300f,
                            alpha = 0.06f
                        )
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SectionHeader(stringResource(R.string.detail_spam_score), color = if (r.isSpam) CatRed else CatGreen)
                    Spacer(Modifier.height(8.dp))
                    SpamScoreGauge(score = if (r.isSpam) r.confidence else 0, isSpam = r.isSpam)
                    if (r.isSpam) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(detectionIcon(r.matchSource), null, tint = CatPeach, modifier = Modifier.size(16.dp))
                            Text(r.matchSource.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = CatPeach)
                        }
                    }
                }
            }
        }

        // Feature D: "Why was this blocked?" — narrative reconstruction from
        // stored matchReason + description + confidence. Shows even for
        // allow-through results so the user understands why *anything*
        // happened (e.g. "This number is in your emergency contacts").
        liveResult?.let { r ->
            val reasoning = remember(r.matchSource, r.description, r.confidence) {
                com.sysadmindoc.callshield.data.BlockReasoning.explain(
                    matchReason = r.matchSource,
                    description = r.description,
                    confidence = r.confidence,
                )
            }
            val accent = if (r.isSpam) CatPeach else CatGreen
            PremiumCard(accentColor = accent) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(stringResource(R.string.block_reasoning_title), color = accent)
                    Spacer(Modifier.height(8.dp))
                    Text(reasoning.headline, fontWeight = FontWeight.SemiBold, color = accent)
                    if (reasoning.bullets.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        reasoning.bullets.forEach { bullet ->
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text("•", color = CatSubtext, modifier = Modifier.width(16.dp))
                                Text(bullet, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                            }
                        }
                    }
                }
            }
        }

        // Block area code
        if (areaCode != null) {
            OutlinedButton(
                onClick = { viewModel.addWildcardRule("+1$areaCode*", false, "Block area code $areaCode" + if (location != null) " ($location)" else "") },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatYellow.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.FilterAlt, null, tint = CatYellow)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_block_area_code, areaCode), color = CatYellow)
            }
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
                if (dbEntry != null) {
                    Spacer(Modifier.height(12.dp))
                    GradientDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(dbEntry.type.replaceFirstChar { it.uppercase() }) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = CatRed.copy(alpha = 0.2f), labelColor = CatRed))
                        AssistChip(onClick = {}, label = { Text("${dbEntry.reports} reports") },
                            colors = AssistChipDefaults.assistChipColors(containerColor = CatPeach.copy(alpha = 0.2f), labelColor = CatPeach))
                    }
                    if (dbEntry.description.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(dbEntry.description, color = CatSubtext, style = MaterialTheme.typography.bodyMedium)
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
                    val reasons = numberCalls.map { it.matchReason }.filter { it.isNotEmpty() }.distinct()
                    if (reasons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        GradientDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.detail_match_reasons), style = MaterialTheme.typography.labelMedium, color = CatOverlay)
                        reasons.forEach { reason ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                                Icon(detectionIcon(reason), null, tint = CatPeach, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(reason.replace("_", " "), style = MaterialTheme.typography.bodySmall, color = CatPeach)
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
                            Icon(if (call.isCall) Icons.Default.Phone else Icons.Default.Sms, null,
                                tint = if (call.isCall) CatRed else CatMauve, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(dateFormat.format(Date(call.timestamp)), style = MaterialTheme.typography.bodySmall, color = CatSubtext, modifier = Modifier.weight(1f))
                            if (call.confidence < 100) Text("${call.confidence}%", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                        }
                        if (call.smsBody != null) {
                            Text(call.smsBody, style = MaterialTheme.typography.bodySmall, color = CatSubtext.copy(alpha = 0.7f), maxLines = 2, modifier = Modifier.padding(start = 24.dp))
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
                        OutlinedButton(
                            onClick = {
                                webLoading = true
                                coroutineScope.launch {
                                    try {
                                        webResult = ExternalLookup.lookupAll(number)
                                    } catch (_: Exception) {}
                                    webLoading = false
                                }
                            },
                            enabled = !webLoading,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (webLoading) CatOverlay.copy(alpha = 0.3f) else CatBlue.copy(alpha = 0.3f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.detail_check_sources),
                                color = if (webLoading) CatOverlay else CatBlue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (webLoading) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CatBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.detail_checking_sources),
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext
                        )
                    }
                }
                webResult?.let { wr ->
                    Spacer(Modifier.height(8.dp))
                    if (wr.totalReports > 0) {
                        Text(
                            stringResource(R.string.detail_reports_across_sources, wr.totalReports, wr.sources.size),
                            color = CatRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            stringResource(R.string.detail_clean_all_sources),
                            color = CatGreen,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    wr.sources.forEach { src ->
                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (src.isSpam) Icons.Default.Warning else Icons.Default.CheckCircle,
                                null, tint = if (src.isSpam) CatRed else CatGreen, modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(src.source, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                            Text(
                                if (src.reports > 0) {
                                    stringResource(R.string.detail_reports_count, src.reports)
                                } else if (src.isSpam) {
                                    stringResource(R.string.detail_flagged)
                                } else {
                                    stringResource(R.string.detail_clean)
                                },
                                style = MaterialTheme.typography.bodySmall, color = CatSubtext
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

        // Actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isBlocked) {
                Button(
                    onClick = {
                        viewModel.blockNumber(number, "spam", "Blocked from detail")
                        hapticConfirm(context)
                        Toast.makeText(context, context.getString(R.string.detail_number_blocked), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatRed),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CatRed.copy(alpha = 0.3f))
                ) {
                    Icon(Icons.Default.Block, null, tint = Black)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_block), color = Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        userBlocked.find { it.number == number }?.let { viewModel.unblockNumber(it) }
                        hapticTick(context)
                        Toast.makeText(context, context.getString(R.string.detail_number_unblocked), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Black)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_unblock), color = Black, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = {
                    val title = Uri.encode("[SPAM] $number"); val body = Uri.encode("## Phone Number\n$number\n\n## Type\nReported from CallShield\n\n## Description\nSeen ${numberCalls.size} times")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new?title=$title&body=$body&labels=spam-report")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatPeach.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Flag, null, tint = CatPeach)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_report), color = CatPeach)
            }
        }

        // Community contribution buttons
        val contributeResult by viewModel.contributeResult.collectAsState()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { hapticTick(context); viewModel.contributeToDatabase(number, dbEntry?.type ?: liveResult?.type ?: "spam") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Favorite, null, tint = Black)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.detail_report_spam), color = Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = { hapticTick(context); viewModel.reportNotSpam(number) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.ThumbUp, null, tint = CatBlue)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.detail_not_spam), color = CatBlue, style = MaterialTheme.typography.labelSmall)
            }
        }
        contributeResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if ("not spam" in it.lowercase() || "contributed" in it.lowercase()) CatGreen else CatRed)
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(4000)
                viewModel.clearContributeResult()
            }
        }

        // FTC fraud report — copies the number + opens reportfraud.ftc.gov.
        // The FTC form doesn't accept URL params, so we do the next-best
        // thing: clipboard-seed the number and tell the user to paste.
        OutlinedButton(
            onClick = {
                hapticTick(context)
                com.sysadmindoc.callshield.data.ReportFraudHelper.report(context, number)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CatPeach.copy(alpha = 0.3f))
        ) {
            Icon(Icons.Default.Gavel, null, tint = CatPeach)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.detail_ftc_complaint), color = CatPeach, style = MaterialTheme.typography.labelSmall)
        }

        // Whitelist / call / share actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.addToWhitelist(number, "Whitelisted from detail") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = CatGreen)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.detail_whitelist), color = CatGreen, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Phone, null, tint = CatBlue)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.detail_call), color = CatBlue, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = {
                    viewModel.shareAsSpam(number, dbEntry?.type ?: liveResult?.type ?: "")
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatYellow.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Share, null, tint = CatYellow)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.detail_share), color = CatYellow, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.5).sp), fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
    }
}

@Composable
fun TimelineRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CatOverlay, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

private fun lookupContactName(context: Context, number: String): String? {
    return try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }
}
