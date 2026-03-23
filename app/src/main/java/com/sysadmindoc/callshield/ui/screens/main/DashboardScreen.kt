package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.service.CallLogScanner
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val totalBlocked by viewModel.totalBlocked.collectAsState()
    val blockedToday by viewModel.blockedToday.collectAsState()
    val spamCount by viewModel.spamCount.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsState()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsState()
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsState()
    val heuristics by viewModel.heuristicsEnabled.collectAsState()
    val smsContent by viewModel.smsContentEnabled.collectAsState()
    val stirShaken by viewModel.stirShakenEnabled.collectAsState()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()
    val smsScanResult by viewModel.smsScanResult.collectAsState()
    val lastSync by viewModel.lastSyncTimestamp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Permission check banner
        val context = LocalContext.current
        val missingPerms = remember {
            listOf(
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.RECEIVE_SMS
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        if (missingPerms.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CatRed.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${missingPerms.size} permissions missing. Open Settings to grant.", color = CatRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Shield status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val shieldActive = blockCallsEnabled || blockSmsEnabled
                // Pulsing shield animation
                val pulseAnim = rememberInfiniteTransition(label = "pulse")
                val pulseScale by pulseAnim.animateFloat(
                    initialValue = 1f, targetValue = 1.08f, label = "scale",
                    animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse)
                )
                Icon(
                    imageVector = if (shieldActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                    contentDescription = null,
                    tint = if (shieldActive) CatGreen else CatRed,
                    modifier = Modifier.size(64.dp).graphicsLayer { if (shieldActive) { scaleX = pulseScale; scaleY = pulseScale } }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (shieldActive) "Protection Active" else "Protection Disabled",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (shieldActive) CatGreen else CatRed
                )
                Spacer(Modifier.height(4.dp))
                Text("$spamCount numbers in database", style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                val engineCount = listOf(true, stirShaken, heuristics, smsContent, neighborSpoof).count { it }
                Text(
                    text = "$engineCount detection engines active" + if (aggressiveMode) " | AGGRESSIVE" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aggressiveMode) CatRed else CatOverlay
                )
                // Sync freshness
                if (lastSync > 0) {
                    val ago = System.currentTimeMillis() - lastSync
                    val freshText = when {
                        ago < 3_600_000 -> "Synced just now"
                        ago < 86_400_000 -> "Synced ${ago / 3_600_000}h ago"
                        else -> "Synced ${ago / 86_400_000}d ago"
                    }
                    val freshColor = when {
                        ago < 86_400_000 -> CatGreen
                        ago < 172_800_000 -> CatYellow
                        else -> CatRed
                    }
                    Text(freshText, style = MaterialTheme.typography.labelSmall, color = freshColor)
                }
            }
        }

        // Stats row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), "Today", blockedToday.toString(), Icons.Default.Today, CatBlue)
            StatCard(Modifier.weight(1f), "Total Blocked", totalBlocked.toString(), Icons.Default.Block, CatPeach)
        }

        // Quick toggles
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                QuickToggle(Icons.Default.Phone, "Block Calls", blockCallsEnabled) { viewModel.setBlockCalls(it) }
                QuickToggle(Icons.Default.Sms, "Block SMS", blockSmsEnabled) { viewModel.setBlockSms(it) }
            }
        }

        // Blocking profiles
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick Profiles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                // Row 1: Work, Personal, Sleep
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(Modifier.weight(1f), "Work", CatBlue) { viewModel.applyProfile(BlockingProfiles.Profile.WORK) }
                    ProfileChip(Modifier.weight(1f), "Personal", CatGreen) { viewModel.applyProfile(BlockingProfiles.Profile.PERSONAL) }
                    ProfileChip(Modifier.weight(1f), "Sleep", CatMauve) { viewModel.applyProfile(BlockingProfiles.Profile.SLEEP) }
                }
                Spacer(Modifier.height(6.dp))
                // Row 2: Maximum, Off
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(Modifier.weight(1f), "Maximum", CatRed) { viewModel.applyProfile(BlockingProfiles.Profile.MAX) }
                    ProfileChip(Modifier.weight(1f), "Off", CatOverlay) { viewModel.applyProfile(BlockingProfiles.Profile.OFF) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Action buttons row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Sync button
            Button(
                onClick = { viewModel.sync() },
                modifier = Modifier.weight(1f),
                enabled = syncState !is SyncState.Syncing,
                colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (syncState is SyncState.Syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Black)
                } else {
                    Icon(Icons.Default.Sync, null, tint = Black)
                }
                Spacer(Modifier.width(6.dp))
                Text("Sync", color = Black, fontWeight = FontWeight.Bold)
            }

            // Scan call log
            Button(
                onClick = { viewModel.scanCallLog() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CatBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Phone, null, tint = Black)
                Spacer(Modifier.width(6.dp))
                Text("Scan Calls", color = Black, fontWeight = FontWeight.Bold)
            }
        }

        // SMS scan button
        Button(
            onClick = { viewModel.scanSmsInbox() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CatMauve),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Sms, null, tint = Black)
            Spacer(Modifier.width(6.dp))
            Text("Scan SMS Inbox", color = Black, fontWeight = FontWeight.Bold)
        }

        // Sync status
        AnimatedVisibility(syncState is SyncState.Success || syncState is SyncState.Error) {
            val isSuccess = syncState is SyncState.Success
            val message = when (syncState) {
                is SyncState.Success -> (syncState as SyncState.Success).message
                is SyncState.Error -> (syncState as SyncState.Error).message
                else -> ""
            }
            Text(message, color = if (isSuccess) CatGreen else CatRed, style = MaterialTheme.typography.bodySmall)
        }

        // Feature 3: Scan results
        scanResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Call Log Scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scanned ${result.totalScanned} unique numbers, found ${result.spamFound} spam",
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (spam in result.spamNumbers.take(5)) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(spam.number, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${spam.callCount}x | ${spam.matchReason}",
                                    style = MaterialTheme.typography.bodySmall, color = CatSubtext
                                )
                            }
                            TextButton(onClick = { viewModel.blockNumber(spam.number, spam.type) }) {
                                Text("Block", color = CatRed)
                            }
                        }
                    }
                    if (result.spamNumbers.size > 5) {
                        Text("+ ${result.spamNumbers.size - 5} more...", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // SMS scan results
        smsScanResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SMS Inbox Scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scanned ${result.totalScanned} messages, found ${result.spamFound} spam",
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (sms in result.spamMessages.take(5)) {
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(com.sysadmindoc.callshield.data.PhoneFormatter.format(sms.number), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(sms.body, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 1)
                                Text(sms.matchReason.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = CatPeach)
                            }
                            TextButton(onClick = { viewModel.blockNumber(sms.number, sms.type) }) {
                                Text("Block", color = CatRed)
                            }
                        }
                    }
                    if (result.spamMessages.size > 5) {
                        Text("+ ${result.spamMessages.size - 5} more...", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = CatSubtext, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen))
    }
}

@Composable
fun ProfileChip(modifier: Modifier, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatCard(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color) {
    val targetValue = value.toIntOrNull() ?: 0
    val animatedValue by animateIntAsState(targetValue = targetValue, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "counter")

    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(animatedValue.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text(title, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
    }
}
