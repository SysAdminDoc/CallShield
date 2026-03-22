package com.sysadmindoc.callshield.ui.screens.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val timeBlock by viewModel.timeBlockEnabled.collectAsState()
    val timeStart by viewModel.timeBlockStart.collectAsState()
    val timeEnd by viewModel.timeBlockEnd.collectAsState()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsState()

    val roleManager = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(Context.ROLE_SERVICE) as? RoleManager else null
    }
    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Call Screening Role
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Call Screening", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        colors = ButtonDefaults.buttonColors(containerColor = CatBlue), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhoneCallback, null, tint = Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Call Screener", color = Black, fontWeight = FontWeight.Bold)
                    }
                    // Overlay permission button
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp)
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
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("Block Spam SMS", "Filter texts from spam numbers and content", Icons.Default.SmsOff, blockSms) { viewModel.setBlockSms(it) }
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("Block Unknown Numbers", "Reject calls with hidden/no caller ID", Icons.Default.QuestionMark, blockUnknown) { viewModel.setBlockUnknown(it) }
        }

        // Safety
        SettingsCard("Safety") {
            SettingsToggle("Contact Whitelist", "Never block numbers in your contacts", Icons.Default.Contacts, contactWhitelist) { viewModel.setContactWhitelist(it) }
        }

        // Detection engines
        SettingsCard("Detection Engines") {
            SettingsToggle("STIR/SHAKEN", "Block calls failing carrier caller ID auth (Android 11+)", Icons.Default.VerifiedUser, stirShaken) { viewModel.setStirShaken(it) }
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("Neighbor Spoofing", "Flag calls matching your area code + exchange", Icons.Default.Nearby, neighborSpoof) { viewModel.setNeighborSpoof(it) }
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("Heuristic Analysis", "VoIP ranges, premium rate, wangiri, rapid-fire", Icons.Default.Psychology, heuristics) { viewModel.setHeuristics(it) }
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("SMS Content Analysis", "Spam keywords, phishing links, scam patterns", Icons.Default.TextSnippet, smsContent) { viewModel.setSmsContent(it) }
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle("Repeat Caller Auto-Block", "Auto-block numbers that call 3+ times", Icons.Default.Repeat, freqEscalation) { viewModel.setFreqEscalation(it) }
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

        // About
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("CallShield v2.0.0", color = CatSubtext)
                Spacer(Modifier.height(4.dp))
                Text("Open-source spam blocker with 11-layer detection, caller ID overlay, call log scanning, wildcard rules, quiet hours, and community reporting. No API keys, no tracking.", style = MaterialTheme.typography.bodySmall, color = CatOverlay)
            }
        }
    }
}

@Composable
fun HourPicker(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected == 0) "12 AM" else if (selected < 12) "$selected AM" else if (selected == 12) "12 PM" else "${selected - 12} PM"

    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(8.dp)) {
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
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggle(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean, tintColor: androidx.compose.ui.graphics.Color = CatSubtext, onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen))
    }
}
