package com.sysadmindoc.callshield.ui.screens.details

import android.content.Intent
import android.net.Uri
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
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = CatSubtext)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(PhoneFormatter.format(number), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(PhoneFormatter.formatWithCountryCode(number), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                if (location != null) {
                    Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                }
            }
        }

        // Block area code quick action
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

        // Status card
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
                        AssistChip(
                            onClick = {},
                            label = { Text(dbEntry.type.replaceFirstChar { it.uppercase() }) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = CatRed.copy(alpha = 0.2f), labelColor = CatRed)
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("${dbEntry.reports} reports") },
                            colors = AssistChipDefaults.assistChipColors(containerColor = CatPeach.copy(alpha = 0.2f), labelColor = CatPeach)
                        )
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
                    if (lastSeen != null && lastSeen != firstSeen) {
                        TimelineRow("Last seen", dateFormat.format(Date(lastSeen)))
                    }
                    // Show match reasons
                    val reasons = numberCalls.map { it.matchReason }.filter { it.isNotEmpty() }.distinct()
                    if (reasons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Match reasons:", style = MaterialTheme.typography.labelMedium, color = CatOverlay)
                        reasons.forEach { reason ->
                            Text("  - ${reason.replace("_", " ")}", style = MaterialTheme.typography.bodySmall, color = CatPeach)
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
                            Icon(
                                if (call.isCall) Icons.Default.Phone else Icons.Default.Sms,
                                null, tint = if (call.isCall) CatRed else CatMauve, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(dateFormat.format(Date(call.timestamp)), style = MaterialTheme.typography.bodySmall, color = CatSubtext, modifier = Modifier.weight(1f))
                            if (call.confidence < 100) {
                                Text("${call.confidence}%", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                            }
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
                Button(
                    onClick = { viewModel.blockNumber(number, "spam", "Blocked from detail view") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Block, null, tint = Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Block", color = Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        val entry = userBlocked.find { it.number == number }
                        if (entry != null) viewModel.unblockNumber(entry)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Unblock", color = Black, fontWeight = FontWeight.Bold)
                }
            }

            // Report button
            OutlinedButton(
                onClick = {
                    val title = Uri.encode("[SPAM] $number")
                    val body = Uri.encode("## Phone Number\n$number\n\n## Type\nReported from CallShield\n\n## Description\nSeen ${numberCalls.size} times")
                    val url = "https://github.com/SysAdminDoc/CallShield/issues/new?title=$title&body=$body&labels=spam-report"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Flag, null, tint = CatPeach)
                Spacer(Modifier.width(6.dp))
                Text("Report", color = CatPeach)
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
