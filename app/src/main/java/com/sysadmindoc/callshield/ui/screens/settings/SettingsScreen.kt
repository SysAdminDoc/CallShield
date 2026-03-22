package com.sysadmindoc.callshield.ui.screens.settings

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
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

    val roleManager = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        } else null
    }

    val screeningLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result handled by OS */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Call Screening Role
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Call Screening",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "CallShield needs to be set as your default call screening app to block spam calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
                            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                            screeningLauncher.launch(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CatBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhoneCallback, null, tint = Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Set as Call Screener", color = Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Blocking settings
        SettingsCard("Blocking") {
            SettingsToggle(
                title = "Block Spam Calls",
                subtitle = "Reject calls from known spam numbers",
                icon = Icons.Default.PhoneDisabled,
                checked = blockCalls,
                onCheckedChange = { viewModel.setBlockCalls(it) }
            )
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle(
                title = "Block Spam SMS",
                subtitle = "Filter text messages from spam numbers and content",
                icon = Icons.Default.SmsOff,
                checked = blockSms,
                onCheckedChange = { viewModel.setBlockSms(it) }
            )
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle(
                title = "Block Unknown Numbers",
                subtitle = "Reject calls with hidden/no caller ID",
                icon = Icons.Default.QuestionMark,
                checked = blockUnknown,
                onCheckedChange = { viewModel.setBlockUnknown(it) }
            )
        }

        // Safety
        SettingsCard("Safety") {
            SettingsToggle(
                title = "Contact Whitelist",
                subtitle = "Never block numbers in your contacts",
                icon = Icons.Default.Contacts,
                checked = contactWhitelist,
                onCheckedChange = { viewModel.setContactWhitelist(it) }
            )
        }

        // Detection engines
        SettingsCard("Detection Engines") {
            SettingsToggle(
                title = "STIR/SHAKEN Verification",
                subtitle = "Block calls that fail carrier caller ID authentication (Android 11+)",
                icon = Icons.Default.VerifiedUser,
                checked = stirShaken,
                onCheckedChange = { viewModel.setStirShaken(it) }
            )
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle(
                title = "Neighbor Spoofing Detection",
                subtitle = "Flag calls from numbers matching your area code + exchange",
                icon = Icons.Default.Nearby,
                checked = neighborSpoof,
                onCheckedChange = { viewModel.setNeighborSpoof(it) }
            )
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle(
                title = "Heuristic Analysis",
                subtitle = "On-device detection: VoIP ranges, premium rate, wangiri, rapid-fire patterns",
                icon = Icons.Default.Psychology,
                checked = heuristics,
                onCheckedChange = { viewModel.setHeuristics(it) }
            )
            HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
            SettingsToggle(
                title = "SMS Content Analysis",
                subtitle = "Scan message text for spam keywords, phishing links, scam patterns",
                icon = Icons.Default.TextSnippet,
                checked = smsContent,
                onCheckedChange = { viewModel.setSmsContent(it) }
            )
        }

        // Aggressive mode
        SettingsCard("Power Mode") {
            SettingsToggle(
                title = "Aggressive Blocking",
                subtitle = "Lower detection thresholds. Blocks more spam but may have false positives. Contacts are always safe.",
                icon = Icons.Default.Security,
                checked = aggressiveMode,
                onCheckedChange = { viewModel.setAggressiveMode(it) },
                tintColor = CatRed
            )
        }

        // About
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text("CallShield v1.1.0", color = CatSubtext)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open-source spam call & text blocker with 8-layer detection. " +
                    "Database hosted on GitHub, heuristics run on-device. No API keys, no tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatOverlay
                )
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tintColor: androidx.compose.ui.graphics.Color = CatSubtext
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CatGreen)
        )
    }
}
