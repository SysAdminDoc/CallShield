package com.sysadmindoc.callshield.ui.screens.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
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
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Blocking",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
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
                    subtitle = "Filter text messages from spam numbers",
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
        }

        // Detection settings
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Detection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                SettingsToggle(
                    title = "STIR/SHAKEN Verification",
                    subtitle = "Block calls that fail carrier caller ID verification (Android 11+)",
                    icon = Icons.Default.VerifiedUser,
                    checked = stirShaken,
                    onCheckedChange = { viewModel.setStirShaken(it) }
                )
                HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
                SettingsToggle(
                    title = "Neighbor Spoofing Detection",
                    subtitle = "Flag calls from numbers similar to yours",
                    icon = Icons.Default.Nearby,
                    checked = neighborSpoof,
                    onCheckedChange = { viewModel.setNeighborSpoof(it) }
                )
            }
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
                Text("CallShield v1.0.0", color = CatSubtext)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open-source spam call & text blocker. Database hosted on GitHub, synced to your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatOverlay
                )
            }
        }
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = CatSubtext, modifier = Modifier.size(24.dp))
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
