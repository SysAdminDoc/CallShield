package com.sysadmindoc.callshield.ui.screens.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamCheckResult
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.lookup.SpamScoreGauge
import com.sysadmindoc.callshield.ui.screens.lookup.detectionIcon
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NumberDetailScreen(number: String, viewModel: MainViewModel, onBack: () -> Unit) {
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
    val contactName = remember(number) { lookupContactName(context, number) }

    // Live spam check result
    var liveResult by remember { mutableStateOf<SpamCheckResult?>(null) }
    LaunchedEffect(number) {
        liveResult = withContext(Dispatchers.IO) { SpamRepository.getInstance(context).isSpam(number) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CatSubtext) }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (contactName != null) {
                    Text(contactName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CatGreen)
                }
                Text(PhoneFormatter.format(number), style = if (contactName != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(PhoneFormatter.formatWithCountryCode(number), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
            }
            // Copy button
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Phone", number))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, "Copy", tint = CatSubtext) }
        }

        // Spam score gauge (live check)
        liveResult?.let { r ->
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Spam Score", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

        // Block area code
        if (areaCode != null) {
            OutlinedButton(
                onClick = { viewModel.addWildcardRule("+1$areaCode*", false, "Block area code $areaCode" + if (location != null) " ($location)" else "") },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FilterAlt, null, tint = CatYellow)
                Spacer(Modifier.width(6.dp))
                Text("Block all $areaCode numbers", color = CatYellow)
            }
        }

        // Stats
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip("Calls", callCount.toString(), CatRed)
                    StatChip("SMS", smsCount.toString(), CatMauve)
                    StatChip("Total", numberCalls.size.toString(), CatBlue)
                }
                if (dbEntry != null) {
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
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    TimelineRow("First seen", dateFormat.format(Date(firstSeen)))
                    if (lastSeen != null && lastSeen != firstSeen) TimelineRow("Last seen", dateFormat.format(Date(lastSeen)))
                    val reasons = numberCalls.map { it.matchReason }.filter { it.isNotEmpty() }.distinct()
                    if (reasons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Match reasons:", style = MaterialTheme.typography.labelMedium, color = CatOverlay)
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
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Recent Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

        // Actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isBlocked) {
                Button(onClick = { viewModel.blockNumber(number, "spam", "Blocked from detail") }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatRed), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Block, null, tint = Black); Spacer(Modifier.width(6.dp)); Text("Block", color = Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = { userBlocked.find { it.number == number }?.let { viewModel.unblockNumber(it) } }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatGreen), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Black); Spacer(Modifier.width(6.dp)); Text("Unblock", color = Black, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = {
                val title = Uri.encode("[SPAM] $number"); val body = Uri.encode("## Phone Number\n$number\n\n## Type\nReported from CallShield\n\n## Description\nSeen ${numberCalls.size} times")
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new?title=$title&body=$body&labels=spam-report")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Flag, null, tint = CatPeach); Spacer(Modifier.width(6.dp)); Text("Report", color = CatPeach)
            }
        }

        // Whitelist / call / share actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.addToWhitelist(number, "Whitelisted from detail") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = CatGreen); Spacer(Modifier.width(4.dp)); Text("Whitelist", color = CatGreen, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Phone, null, tint = CatBlue); Spacer(Modifier.width(4.dp)); Text("Call", color = CatBlue, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = {
                viewModel.shareAsSpam(number, dbEntry?.type ?: liveResult?.type ?: "")
            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Share, null, tint = CatYellow); Spacer(Modifier.width(4.dp)); Text("Share", color = CatYellow, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
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
