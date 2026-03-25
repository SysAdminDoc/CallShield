package com.sysadmindoc.callshield.ui.screens.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val blockCalls by viewModel.blockCallsEnabled.collectAsState()
    val blockSms by viewModel.blockSmsEnabled.collectAsState()
    val blockUnknown by viewModel.blockUnknownEnabled.collectAsState()
    val stirShaken by viewModel.stirShakenEnabled.collectAsState()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsState()
    val heuristics by viewModel.heuristicsEnabled.collectAsState()
    val smsContent by viewModel.smsContentEnabled.collectAsState()
    val contactWhitelist by viewModel.contactWhitelistEnabled.collectAsState()
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsState()
    val autoCleanup by viewModel.autoCleanupEnabled.collectAsState()
    val cleanupDays by viewModel.cleanupDays.collectAsState()
    val timeBlock by viewModel.timeBlockEnabled.collectAsState()
    val timeStart by viewModel.timeBlockStart.collectAsState()
    val timeEnd by viewModel.timeBlockEnd.collectAsState()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsState()
    val mlScorer by viewModel.mlScorerEnabled.collectAsState()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsState()
    val abstractApiKey by viewModel.abstractApiKey.collectAsState()

    val roleManager = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(Context.ROLE_SERVICE) as? RoleManager else null
    }
    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Call Screening Role
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Call Screening", CatBlue)
                Spacer(Modifier.height(4.dp))
                Text("Set CallShield as your default call screening app.", style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
                                screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CatBlue), shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PhoneCallback, null, tint = Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Call Screener", color = Black, fontWeight = FontWeight.Bold)
                    }
                    // Overlay permission button
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Layers, null, tint = CatMauve)
                        Spacer(Modifier.width(6.dp))
                        Text("Overlay", color = CatMauve)
                    }
                }
            }
        }

        // Blocking
        SettingsCard("Blocking") {
            SettingsToggle("Block Spam Calls", "Reject calls from known spam numbers", Icons.Default.PhoneDisabled, blockCalls) { viewModel.setBlockCalls(it) }
            GradientDivider()
            SettingsToggle("Block Spam SMS", "Filter texts from spam numbers and content", Icons.Default.SpeakerNotesOff, blockSms) { viewModel.setBlockSms(it) }
            GradientDivider()
            SettingsToggle("Block Unknown Numbers", "Reject calls with hidden/no caller ID", Icons.Default.QuestionMark, blockUnknown) { viewModel.setBlockUnknown(it) }
        }

        // Safety
        SettingsCard("Safety") {
            SettingsToggle("Contact Whitelist", "Never block numbers in your contacts", Icons.Default.Contacts, contactWhitelist) { viewModel.setContactWhitelist(it) }
        }

        // Detection engines
        SettingsCard("Detection Engines") {
            SettingsToggle("STIR/SHAKEN", "Block calls failing carrier caller ID auth (Android 11+)", Icons.Default.VerifiedUser, stirShaken) { viewModel.setStirShaken(it) }
            GradientDivider()
            SettingsToggle("Neighbor Spoofing", "Flag calls matching your area code + exchange", Icons.Default.NearMe, neighborSpoof) { viewModel.setNeighborSpoof(it) }
            GradientDivider()
            SettingsToggle("Heuristic Analysis", "VoIP ranges, premium rate, wangiri, rapid-fire", Icons.Default.Psychology, heuristics) { viewModel.setHeuristics(it) }
            GradientDivider()
            SettingsToggle("SMS Content Analysis", "Spam keywords, phishing links, scam patterns", Icons.AutoMirrored.Filled.TextSnippet, smsContent) { viewModel.setSmsContent(it) }
            GradientDivider()
            SettingsToggle("Repeat Caller Auto-Block", "Auto-block numbers that call 3+ times", Icons.Default.Repeat, freqEscalation) { viewModel.setFreqEscalation(it) }
            GradientDivider()
            SettingsToggle("ML Spam Scorer", "On-device logistic regression model. No internet required.", Icons.Default.SmartToy, mlScorer) { viewModel.setMlScorer(it) }
            GradientDivider()
            SettingsToggle("RCS Message Filter", "Block RCS spam via Notification Access. Covers Google/Samsung Messages.", Icons.Default.MarkChatRead, rcsFilter) { viewModel.setRcsFilter(it) }
            if (rcsFilter) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.NotificationsActive, null, tint = CatMauve)
                    Spacer(Modifier.width(6.dp))
                    Text("Grant Notification Access", color = CatMauve)
                }
            }
        }

        // Feature 9: Time-based blocking
        SettingsCard("Quiet Hours") {
            SettingsToggle("Block unknowns during quiet hours", "Block all non-contact calls during set hours", Icons.Default.Bedtime, timeBlock) { viewModel.setTimeBlock(it) }
            if (timeBlock) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start", style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                        HourPicker(timeStart) { viewModel.setTimeBlockStart(it) }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("End", style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                        HourPicker(timeEnd) { viewModel.setTimeBlockEnd(it) }
                    }
                }
            }
        }

        // Power mode
        SettingsCard("Power Mode") {
            SettingsToggle("Aggressive Blocking", "Lower thresholds. More spam blocked, possible false positives. Contacts always safe.", Icons.Default.Security, aggressiveMode, tintColor = CatRed) { viewModel.setAggressiveMode(it) }
        }

        // Auto-cleanup
        SettingsCard("Log Cleanup") {
            SettingsToggle("Auto-cleanup old entries", "Remove blocked log entries older than retention period", Icons.Default.AutoDelete, autoCleanup) { viewModel.setAutoCleanup(it) }
            if (autoCleanup) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep for:", style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                    listOf(7, 14, 30, 90).forEach { days ->
                        FilterChip(
                            selected = cleanupDays == days, onClick = { viewModel.setCleanupDays(days) },
                            label = { Text("${days}d") },
                            border = BorderStroke(1.dp, if (cleanupDays == days) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen)
                        )
                    }
                }
            }
        }

        // Export log
        SettingsCard("Export") {
            Button(
                onClick = { hapticTick(context); viewModel.exportLog() },
                colors = ButtonDefaults.buttonColors(containerColor = CatBlue),
                border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.FileDownload, null, tint = Black)
                Spacer(Modifier.width(6.dp))
                Text("Export Blocked Log as CSV", color = Black, fontWeight = FontWeight.Bold)
            }
            Text("Export all blocked calls/SMS as a CSV file for analysis.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        }

        // Backup/restore
        SettingsCard("Backup & Restore") {
            val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { viewModel.restore(it) }
            }
            val restoreResult by viewModel.restoreResult.collectAsState()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { hapticTick(context); viewModel.backup() },
                    colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.Backup, null, tint = Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Backup", color = Black, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { hapticTick(context); restoreLauncher.launch("application/json") },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.Restore, null, tint = CatBlue)
                    Spacer(Modifier.width(6.dp))
                    Text("Restore", color = CatBlue)
                }
            }
            restoreResult?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = CatGreen)
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(4000)
                    viewModel.clearRestoreResult()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Includes blocklist, whitelist, wildcard rules.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        }

        // Advanced — optional API key for caller name lookup
        SettingsCard("Advanced") {
            var apiKeyInput by remember { mutableStateOf(abstractApiKey) }
            Text("AbstractAPI Key (optional)", style = MaterialTheme.typography.bodyMedium)
            Text("Free 250 lookups/month for carrier & line-type enrichment in the caller ID overlay. Never used for blocking.", style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        viewModel.setAbstractApiKey(apiKeyInput.trim())
                        hapticTick(context)
                        android.widget.Toast.makeText(context, if (apiKeyInput.isBlank()) "API key cleared" else "API key saved", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save", color = CatBlue)
                    }
                }
            )
        }

        // About
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("CallShield v1.2.7", color = CatSubtext, style = MaterialTheme.typography.bodySmall)
                Text("Open-source spam blocker. No subscriptions, no tracking.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
            }
        }
    }
}

@Composable
fun HourPicker(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected == 0) "12 AM" else if (selected < 12) "$selected AM" else if (selected == 12) "12 PM" else "${selected - 12} PM"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceBright)
        ) {
            Text(label, color = CatText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (h in 0..23) {
                val l = if (h == 0) "12 AM" else if (h < 12) "$h AM" else if (h == 12) "12 PM" else "${h - 12} PM"
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(h); expanded = false })
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    PremiumCard {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionHeader(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggle(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean, tintColor: androidx.compose.ui.graphics.Color = CatSubtext, onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(tintColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(6.dp)
        ) {
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = { hapticTick(context); onCheckedChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black))
    }
}
