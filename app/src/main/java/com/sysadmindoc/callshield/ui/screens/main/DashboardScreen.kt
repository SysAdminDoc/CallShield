package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val mlScorer by viewModel.mlScorerEnabled.collectAsState()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsState()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsState()
    val blockedThisWeek by viewModel.blockedThisWeek.collectAsState()
    val blockedLastWeek by viewModel.blockedLastWeek.collectAsState()
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()
    val smsScanResult by viewModel.smsScanResult.collectAsState()
    val lastSync by viewModel.lastSyncTimestamp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = CatRed
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${missingPerms.size} permissions missing. Open Settings to grant.",
                        color = CatRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Shield status hero card — entrance animation
        var heroVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { heroVisible = true }
        val heroAlpha by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing), label = "heroAlpha"
        )
        val heroScale by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0.95f,
            animationSpec = tween(600, easing = FastOutSlowInEasing), label = "heroScale"
        )
        PremiumCard(
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                alpha = heroAlpha; scaleX = heroScale; scaleY = heroScale
            },
            accentColor = CatGreen
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
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
                    contentDescription = if (shieldActive) "Protection active" else "Protection disabled",
                    tint = if (shieldActive) CatGreen else CatRed,
                    modifier = Modifier
                        .size(64.dp)
                        .accentGlow(CatGreen, 400f, 0.06f)
                        .graphicsLayer {
                            if (shieldActive) {
                                scaleX = pulseScale; scaleY = pulseScale
                            }
                        }
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (shieldActive) "Protection Active" else "Protection Disabled",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (shieldActive) CatGreen else CatRed
                )
                Spacer(Modifier.height(6.dp))
                if (spamCount == 0) {
                    Text("No spam database loaded", style = MaterialTheme.typography.bodyMedium, color = CatYellow)
                    Text("Tap Sync below to download", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                } else {
                    Text("$spamCount numbers in database", style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                }
                Spacer(Modifier.height(4.dp))
                val engineCount = listOf(true, stirShaken, heuristics, smsContent, neighborSpoof, mlScorer, rcsFilter, freqEscalation).count { it }
                Text(
                    text = "$engineCount detection engines active" + if (aggressiveMode) " | AGGRESSIVE" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aggressiveMode) CatRed else CatOverlay
                )
                // Sync freshness
                if (lastSync > 0) {
                    Spacer(Modifier.height(6.dp))
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Modifier.weight(1f), "Today", blockedToday.toString(), Icons.Default.Today, CatBlue)
            StatCard(Modifier.weight(1f), "This Week", blockedThisWeek.toString(), Icons.Default.DateRange, CatMauve)
            StatCard(Modifier.weight(1f), "Total", totalBlocked.toString(), Icons.Default.Block, CatPeach)
        }

        // Weekly trend comparison
        if (blockedThisWeek > 0 || blockedLastWeek > 0) {
            val diff = blockedThisWeek - blockedLastWeek
            val trendIcon = when {
                diff > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                diff < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                else -> Icons.AutoMirrored.Filled.TrendingFlat
            }
            val trendColor = when {
                diff > 0 -> CatRed
                diff < 0 -> CatGreen
                else -> CatSubtext
            }
            val trendText = when {
                diff > 0 -> "$diff more than last week"
                diff < 0 -> "${-diff} fewer than last week"
                else -> "Same as last week"
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(trendIcon, null, tint = trendColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(trendText, style = MaterialTheme.typography.labelSmall, color = trendColor)
            }
        }

        // Last blocked preview
        val lastBlocked = blockedCalls.firstOrNull { it.wasBlocked }
        if (lastBlocked != null) {
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                onClick = { viewModel.openNumberDetail(lastBlocked.number) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (lastBlocked.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                        null,
                        tint = if (lastBlocked.isCall) CatRed else CatMauve,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Last blocked: ${com.sysadmindoc.callshield.data.PhoneFormatter.format(lastBlocked.number)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        val ago = System.currentTimeMillis() - lastBlocked.timestamp
                        val agoText = when {
                            ago < 60_000 -> "Just now"
                            ago < 3_600_000 -> "${ago / 60_000}m ago"
                            ago < 86_400_000 -> "${ago / 3_600_000}h ago"
                            else -> "${ago / 86_400_000}d ago"
                        }
                        Text(
                            "$agoText · ${lastBlocked.matchReason.replace("_", " ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CatOverlay
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = CatOverlay, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Quick toggles
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader("Quick Controls", CatGreen)
                Spacer(Modifier.height(12.dp))
                QuickToggle(Icons.Default.Phone, "Block Calls", blockCallsEnabled) { viewModel.setBlockCalls(it) }
                GradientDivider(modifier = Modifier.padding(vertical = 4.dp))
                QuickToggle(Icons.Default.Sms, "Block SMS", blockSmsEnabled) { viewModel.setBlockSms(it) }
            }
        }

        // Blocking profiles
        val activeProfile by viewModel.activeProfile.collectAsState()
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader("Quick Profiles", CatMauve)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(Modifier.weight(1f), "Work", CatBlue, activeProfile == BlockingProfiles.Profile.WORK) { viewModel.applyProfile(BlockingProfiles.Profile.WORK) }
                    ProfileChip(Modifier.weight(1f), "Personal", CatGreen, activeProfile == BlockingProfiles.Profile.PERSONAL) { viewModel.applyProfile(BlockingProfiles.Profile.PERSONAL) }
                    ProfileChip(Modifier.weight(1f), "Sleep", CatMauve, activeProfile == BlockingProfiles.Profile.SLEEP) { viewModel.applyProfile(BlockingProfiles.Profile.SLEEP) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(Modifier.weight(1f), "Maximum", CatRed, activeProfile == BlockingProfiles.Profile.MAX) { viewModel.applyProfile(BlockingProfiles.Profile.MAX) }
                    ProfileChip(Modifier.weight(1f), "Off", CatOverlay, activeProfile == BlockingProfiles.Profile.OFF) { viewModel.applyProfile(BlockingProfiles.Profile.OFF) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Action buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { hapticTick(context); viewModel.sync() },
                modifier = Modifier.weight(1f).height(48.dp),
                enabled = syncState !is SyncState.Syncing,
                colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f))
            ) {
                if (syncState is SyncState.Syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Black)
                } else {
                    Icon(Icons.Default.Sync, null, tint = Black)
                }
                Spacer(Modifier.width(6.dp))
                Text("Sync", color = Black, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { hapticTick(context); viewModel.scanCallLog() },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CatBlue),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Phone, null, tint = Black)
                Spacer(Modifier.width(6.dp))
                Text("Scan Calls", color = Black, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = { hapticTick(context); viewModel.scanSmsInbox() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CatMauve),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CatMauve.copy(alpha = 0.3f))
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
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (isSuccess) CatGreen else CatRed
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint = if (isSuccess) CatGreen else CatRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(message, color = if (isSuccess) CatGreen else CatRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Call log scan results
        scanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (result.error != null) CatRed else if (result.spamFound > 0) CatRed else CatGreen
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionHeader("Call Log Scan", CatBlue)
                    Spacer(Modifier.height(10.dp))
                    if (result.error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(result.error, color = CatRed, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                    Text(
                        "Scanned ${result.totalScanned} unique numbers, found ${result.spamFound} spam",
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (spam in result.spamNumbers.take(5)) {
                        Spacer(Modifier.height(6.dp))
                        GradientDivider()
                        Spacer(Modifier.height(6.dp))
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
                        Spacer(Modifier.height(4.dp))
                        Text("+ ${result.spamNumbers.size - 5} more...", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    }
                    } // else (no error)
                }
            }
        }

        // SMS scan results
        smsScanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (result.error != null) CatRed else if (result.spamFound > 0) CatRed else CatGreen
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionHeader("SMS Inbox Scan", CatMauve)
                    Spacer(Modifier.height(10.dp))
                    if (result.error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(result.error, color = CatRed, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                    Text(
                        "Scanned ${result.totalScanned} messages, found ${result.spamFound} spam",
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (sms in result.spamMessages.take(5)) {
                        Spacer(Modifier.height(6.dp))
                        GradientDivider()
                        Spacer(Modifier.height(6.dp))
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
                        Spacer(Modifier.height(4.dp))
                        Text("+ ${result.spamMessages.size - 5} more...", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    }
                    } // else (no error)
                }
            }
        }

        // Smart suggestions
        val topAreaCodes = remember(blockedCalls) {
            blockedCalls.mapNotNull { com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup.getAreaCode(it.number) }
                .groupBy { it }.mapValues { it.value.size }
                .filter { it.value >= 5 }
                .entries.sortedByDescending { it.value }.take(3)
        }
        if (topAreaCodes.isNotEmpty()) {
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = CatYellow
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, null, tint = CatYellow, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Smart Suggestions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    topAreaCodes.forEachIndexed { index, (ac, count) ->
                        if (index > 0) {
                            GradientDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                        val loc = com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup.lookup("+1$ac") ?: ac
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$count spam from $ac ($loc)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.addWildcardRule("+1$ac*", false, "Block $ac ($loc)") }) {
                                Text("Block $ac", color = CatYellow, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun QuickToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val tintColor = if (checked) CatGreen else CatSubtext
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tintColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(20.dp))
        }
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = { hapticTick(context); onChanged(it) }, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black))
    }
}

@Composable
fun ProfileChip(modifier: Modifier, label: String, color: androidx.compose.ui.graphics.Color, isActive: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { hapticConfirm(context); onClick() },
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isActive) color.copy(alpha = 0.6f) else color.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            containerColor = if (isActive) color.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent
        )
    ) {
        if (isActive) {
            Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatCard(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color) {
    val targetValue = value.toIntOrNull() ?: 0
    val animatedValue by animateIntAsState(targetValue = targetValue, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "counter")

    PremiumCard(modifier = modifier, accentColor = color.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(animatedValue.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text(title, style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp), color = CatSubtext)
        }
    }
}
