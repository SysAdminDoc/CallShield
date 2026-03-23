package com.sysadmindoc.callshield.ui.screens.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BlockedLogScreen(viewModel: MainViewModel) {
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    var filterMode by remember { mutableIntStateOf(0) }
    var grouped by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filtered = when (filterMode) {
        1 -> blockedCalls.filter { it.isCall }
        2 -> blockedCalls.filter { !it.isCall }
        else -> blockedCalls
    }

    // Grouped view: collapse by number
    val groupedList = if (grouped) {
        filtered.groupBy { it.number }.map { (number, calls) ->
            calls.first().copy() to calls.size
        }.sortedByDescending { it.second }
    } else null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = Black
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = filterMode == 0, onClick = { filterMode = 0 }, label = { Text("All (${blockedCalls.size})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen))
                FilterChip(selected = filterMode == 1, onClick = { filterMode = 1 }, label = { Text("Calls") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatBlue.copy(alpha = 0.2f), selectedLabelColor = CatBlue))
                FilterChip(selected = filterMode == 2, onClick = { filterMode = 2 }, label = { Text("SMS") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatMauve.copy(alpha = 0.2f), selectedLabelColor = CatMauve))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grouped = !grouped }) {
                    Icon(if (grouped) Icons.Default.ViewList else Icons.Default.GroupWork, "Group", tint = if (grouped) CatYellow else CatOverlay)
                }
                if (blockedCalls.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearLog() }) { Icon(Icons.Default.DeleteSweep, "Clear", tint = CatRed) }
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, null, tint = CatGreen.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No blocked items yet", color = CatSubtext)
                    }
                }
            } else if (grouped && groupedList != null) {
                // Grouped view
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(groupedList) { _, (call, count) ->
                        GroupedCallItem(
                            call = call, count = count,
                            onTap = { viewModel.openNumberDetail(call.number) },
                            onBlock = { viewModel.blockNumber(call.number) }
                        )
                    }
                }
            } else {
                // Swipe-to-dismiss list
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(filtered, key = { _, call -> call.id }) { index, call ->
                        val visible = remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(index.toLong().coerceAtMost(15) * 30)
                            visible.value = true
                        }
                        AnimatedVisibility(visible = visible.value, enter = slideInVertically { 40 } + fadeIn()) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            viewModel.deleteLogEntry(call)
                                            scope.launch {
                                                snackbarHost.showSnackbar("Deleted", duration = SnackbarDuration.Short)
                                            }
                                            true
                                        }
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            viewModel.blockNumber(call.number, "spam", "Blocked from log swipe")
                                            scope.launch {
                                                snackbarHost.showSnackbar("${PhoneFormatter.format(call.number)} blocked", duration = SnackbarDuration.Short)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val direction = dismissState.dismissDirection
                                    val color = when (direction) {
                                        SwipeToDismissBoxValue.StartToEnd -> CatYellow.copy(alpha = 0.3f)
                                        SwipeToDismissBoxValue.EndToStart -> CatRed.copy(alpha = 0.3f)
                                        else -> Black
                                    }
                                    val icon = when (direction) {
                                        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Block
                                        SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                                        else -> Icons.Default.Block
                                    }
                                    val align = when (direction) {
                                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                        else -> Alignment.CenterEnd
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(color).padding(horizontal = 20.dp),
                                        contentAlignment = align
                                    ) {
                                        Icon(icon, null, tint = CatText)
                                    }
                                }
                            ) {
                                BlockedCallItem(call = call, onTap = { viewModel.openNumberDetail(call.number) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockedCallItem(call: BlockedCall, onTap: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.combinedClickable(
            onClick = { expanded = !expanded },
            onLongClick = {
                val clip = ClipData.newPlainText("Phone Number", call.number)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied ${PhoneFormatter.format(call.number)}", Toast.LENGTH_SHORT).show()
            }
        )
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (call.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                    contentDescription = if (call.isCall) "Blocked call" else "Blocked SMS",
                    tint = if (call.isCall) CatRed else CatMauve,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(PhoneFormatter.format(call.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dateFormat.format(Date(call.timestamp)), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                        if (location != null) Text(location, style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                    }
                    if (call.matchReason.isNotEmpty()) {
                        val reasonText = call.matchReason.replace("_", " ").replaceFirstChar { it.uppercase() }
                        val confidenceText = if (call.confidence < 100) " (${call.confidence}%)" else ""
                        Text("$reasonText$confidenceText", style = MaterialTheme.typography.labelSmall, color = CatPeach)
                    }
                    if (call.smsBody != null) {
                        Text(call.smsBody, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 2)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Expand", tint = CatOverlay, modifier = Modifier.size(20.dp)
                )
            }

            // Expandable action buttons
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val digits = call.number.filter { it.isDigit() }
                    // Search Google
                    SmallActionButton(Icons.Default.Search, "Google", CatBlue) {
                        val url = "https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                    // Check databases (open number detail)
                    SmallActionButton(Icons.Default.Storage, "Databases", CatGreen) { onTap() }
                    // Copy
                    SmallActionButton(Icons.Default.ContentCopy, "Copy", CatSubtext) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Phone", call.number))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    // Detail
                    SmallActionButton(Icons.Default.Info, "Detail", CatMauve) { onTap() }
                }
            }
        }
    }
}

@Composable
fun SmallActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun GroupedCallItem(call: BlockedCall, count: Int, onTap: () -> Unit, onBlock: () -> Unit) {
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        onClick = onTap
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Count badge
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(CatRed.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("${count}x", color = CatRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(PhoneFormatter.format(call.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                    Text(if (call.isCall) "Call" else "SMS", style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                }
                if (call.matchReason.isNotEmpty()) {
                    Text(call.matchReason.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = CatPeach)
                }
            }
            IconButton(onClick = onBlock) { Icon(Icons.Default.Block, "Block", tint = CatYellow) }
        }
    }
}
