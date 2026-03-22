package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BlockedLogScreen(viewModel: MainViewModel) {
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    var filterMode by remember { mutableIntStateOf(0) } // 0=all, 1=calls, 2=sms

    val filtered = when (filterMode) {
        1 -> blockedCalls.filter { it.isCall }
        2 -> blockedCalls.filter { !it.isCall }
        else -> blockedCalls
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CatGreen.copy(alpha = 0.2f),
                    selectedLabelColor = CatGreen
                )
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                label = { Text("Calls") },
                leadingIcon = { Icon(Icons.Default.Phone, null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CatBlue.copy(alpha = 0.2f),
                    selectedLabelColor = CatBlue
                )
            )
            FilterChip(
                selected = filterMode == 2,
                onClick = { filterMode = 2 },
                label = { Text("SMS") },
                leadingIcon = { Icon(Icons.Default.Sms, null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CatMauve.copy(alpha = 0.2f),
                    selectedLabelColor = CatMauve
                )
            )

            Spacer(Modifier.weight(1f))

            if (blockedCalls.isNotEmpty()) {
                IconButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Default.DeleteSweep, "Clear log", tint = CatRed)
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = CatGreen.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No blocked items yet", color = CatSubtext)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { call ->
                    BlockedCallItem(
                        call = call,
                        onDelete = { viewModel.deleteLogEntry(call) },
                        onBlock = { viewModel.blockNumber(call.number) }
                    )
                }
            }
        }
    }
}

@Composable
fun BlockedCallItem(
    call: BlockedCall,
    onDelete: () -> Unit,
    onBlock: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                imageVector = if (call.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                contentDescription = null,
                tint = if (call.isCall) CatRed else CatMauve,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.number,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = dateFormat.format(Date(call.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                if (call.matchReason.isNotEmpty()) {
                    val reasonText = call.matchReason.replace("_", " ").replaceFirstChar { it.uppercase() }
                    val confidenceText = if (call.confidence < 100) " (${call.confidence}%)" else ""
                    Text(
                        text = "$reasonText$confidenceText",
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach
                    )
                }
                if (call.smsBody != null) {
                    Text(
                        text = call.smsBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                        maxLines = 2
                    )
                }
            }

            // Actions
            IconButton(onClick = onBlock) {
                Icon(Icons.Default.Block, "Block permanently", tint = CatYellow)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, "Remove", tint = CatOverlay)
            }
        }
    }
}
