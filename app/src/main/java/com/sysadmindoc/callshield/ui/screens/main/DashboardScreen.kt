package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Shield status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val shieldActive = blockCallsEnabled || blockSmsEnabled
                Icon(
                    imageVector = if (shieldActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                    contentDescription = null,
                    tint = if (shieldActive) CatGreen else CatRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (shieldActive) "Protection Active" else "Protection Disabled",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (shieldActive) CatGreen else CatRed
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$spamCount numbers in database",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatSubtext
                )
                val engineCount = listOf(true, stirShaken, heuristics, smsContent, neighborSpoof).count { it }
                Text(
                    text = "$engineCount detection engines active" + if (aggressiveMode) " | AGGRESSIVE" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aggressiveMode) CatRed else CatOverlay
                )
            }
        }

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Today",
                value = blockedToday.toString(),
                icon = Icons.Default.Today,
                color = CatBlue
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Total Blocked",
                value = totalBlocked.toString(),
                icon = Icons.Default.Block,
                color = CatPeach
            )
        }

        // Quick toggles
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Quick Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Phone, null, tint = CatSubtext, modifier = Modifier.size(20.dp))
                    Text("Block Calls", modifier = Modifier.weight(1f))
                    Switch(
                        checked = blockCallsEnabled,
                        onCheckedChange = { viewModel.setBlockCalls(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = CatGreen)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Sms, null, tint = CatSubtext, modifier = Modifier.size(20.dp))
                    Text("Block SMS", modifier = Modifier.weight(1f))
                    Switch(
                        checked = blockSmsEnabled,
                        onCheckedChange = { viewModel.setBlockSms(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = CatGreen)
                    )
                }
            }
        }

        // Sync button
        Button(
            onClick = { viewModel.sync() },
            modifier = Modifier.fillMaxWidth(),
            enabled = syncState !is SyncState.Syncing,
            colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
            shape = RoundedCornerShape(12.dp)
        ) {
            when (syncState) {
                is SyncState.Syncing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing...", color = Black)
                }
                else -> {
                    Icon(Icons.Default.Sync, null, tint = Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Sync Database", color = Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sync status
        AnimatedVisibility(syncState is SyncState.Success || syncState is SyncState.Error) {
            val isSuccess = syncState is SyncState.Success
            val message = when (syncState) {
                is SyncState.Success -> (syncState as SyncState.Success).message
                is SyncState.Error -> (syncState as SyncState.Error).message
                else -> ""
            }
            Text(
                text = message,
                color = if (isSuccess) CatGreen else CatRed,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
        }
    }
}
