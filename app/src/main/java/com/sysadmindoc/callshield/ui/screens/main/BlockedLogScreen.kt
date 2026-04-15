package com.sysadmindoc.callshield.ui.screens.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
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
    val context = LocalContext.current
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    var filterMode by rememberSaveable { mutableIntStateOf(0) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

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
                FilterChip(
                    selected = filterMode == 0,
                    onClick = { filterMode = 0 },
                    label = { Text(stringResource(R.string.blocked_log_filter_all, blockedCalls.size)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CatGreen.copy(alpha = 0.2f),
                        selectedLabelColor = CatGreen
                    ),
                    border = BorderStroke(1.dp, if (filterMode == 0) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f))
                )
                FilterChip(
                    selected = filterMode == 1,
                    onClick = { filterMode = 1 },
                    label = { Text(stringResource(R.string.blocked_log_filter_calls)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CatBlue.copy(alpha = 0.2f),
                        selectedLabelColor = CatBlue
                    ),
                    border = BorderStroke(1.dp, if (filterMode == 1) CatBlue.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f))
                )
                FilterChip(
                    selected = filterMode == 2,
                    onClick = { filterMode = 2 },
                    label = { Text(stringResource(R.string.blocked_log_filter_sms)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CatMauve.copy(alpha = 0.2f),
                        selectedLabelColor = CatMauve
                    ),
                    border = BorderStroke(1.dp, if (filterMode == 2) CatMauve.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f))
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grouped = !grouped }) {
                    Icon(
                        if (grouped) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GroupWork,
                        contentDescription = stringResource(if (grouped) R.string.cd_ungroup else R.string.cd_group),
                        tint = if (grouped) CatYellow else CatOverlay
                    )
                }
                if (blockedCalls.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.cd_clear_log), tint = CatRed)
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.cd_no_items),
                            tint = CatGreen.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp).accentGlow(CatGreen, 200f, 0.05f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.blocked_log_no_items), color = CatSubtext)
                    }
                }
            } else if (grouped && groupedList != null) {
                // Grouped view
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(
                        items = groupedList,
                        key = { _, item -> item.first.number }
                    ) { _, (call, count) ->
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
                                            hapticTick(context)
                                            scope.launch {
                                                val result = snackbarHost.showSnackbar(
                                                    message = context.getString(R.string.blocked_log_deleted),
                                                    actionLabel = context.getString(R.string.blocked_log_undo),
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    viewModel.restoreLogEntry(call)
                                                }
                                            }
                                            true
                                        }
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            viewModel.blockNumber(call.number, "spam", "Blocked from log swipe")
                                            hapticConfirm(context)
                                            scope.launch {
                                                snackbarHost.showSnackbar(
                                                    context.getString(
                                                        R.string.blocked_log_number_blocked,
                                                        PhoneFormatter.format(call.number)
                                                    ),
                                                    duration = SnackbarDuration.Short
                                                )
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
                                        else -> SurfaceBright
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
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(color).padding(horizontal = 20.dp),
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

    // Clear log confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.DeleteSweep, null, tint = CatRed, modifier = Modifier.size(32.dp)) },
            title = { Text(stringResource(R.string.blocked_log_clear_title)) },
            text = {
                Text(
                    stringResource(R.string.blocked_log_clear_message, blockedCalls.size),
                    color = CatSubtext
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearLog()
                        hapticConfirm(context)
                        showClearDialog = false
                        scope.launch {
                            snackbarHost.showSnackbar(
                                context.getString(R.string.blocked_log_log_cleared),
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CatRed),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.blocked_log_clear_all), color = Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.blocked_log_cancel), color = CatSubtext)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockedCallItem(call: BlockedCall, onTap: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }
    var expanded by remember { mutableStateOf(false) }

    PremiumCard(
        cornerRadius = 14.dp,
        modifier = Modifier.combinedClickable(
            onClick = onTap,
            onLongClick = {
                val clip = ClipData.newPlainText("Phone Number", call.number)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(
                    context,
                    context.getString(R.string.blocked_log_copied, PhoneFormatter.format(call.number)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (call.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                    contentDescription = stringResource(
                        if (call.isCall) R.string.blocked_log_blocked_call else R.string.blocked_log_blocked_sms
                    ),
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
                        // Feature A: prepend the resolved CallCategory label.
                        // Falls back silently to just the raw reason if the
                        // resolver lands on Unknown — no noise, no mislabels.
                        val category = remember(call.matchReason, call.type, call.confidence) {
                            com.sysadmindoc.callshield.data.CallCategoryResolver.resolveFromLog(
                                matchReason = call.matchReason,
                                type = call.type,
                                description = "",
                                confidence = call.confidence,
                            )
                        }
                        val label = if (category != com.sysadmindoc.callshield.data.CallCategory.Unknown) {
                            "${category.emoji} ${stringResource(category.stringResId)} · $reasonText$confidenceText"
                        } else {
                            "$reasonText$confidenceText"
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = CatPeach)
                    }
                    if (call.smsBody != null) {
                        Text(call.smsBody, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 2)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.cd_collapse else R.string.cd_expand
                        ),
                        tint = CatOverlay,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expandable action buttons
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val digits = call.number.filter { it.isDigit() }
                    // Search Google
                    SmallActionButton(Icons.Default.Search, stringResource(R.string.blocked_log_google), CatBlue) {
                        val url = "https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                    // Check databases (open number detail)
                    SmallActionButton(Icons.Default.Storage, stringResource(R.string.blocked_log_databases), CatGreen) { onTap() }
                    // Copy
                    SmallActionButton(Icons.Default.ContentCopy, stringResource(R.string.blocked_log_copy), CatSubtext) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Phone", call.number))
                        Toast.makeText(context, context.getString(R.string.blocked_log_copied_short), Toast.LENGTH_SHORT).show()
                    }
                    // Detail
                    SmallActionButton(Icons.Default.Info, stringResource(R.string.blocked_log_detail), CatMauve) { onTap() }
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
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun GroupedCallItem(call: BlockedCall, count: Int, onTap: () -> Unit, onBlock: () -> Unit) {
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }

    val accentColor = if (count >= 5) CatRed else if (count >= 3) CatPeach else CatYellow

    PremiumCard(
        cornerRadius = 14.dp,
        accentColor = if (count >= 5) CatRed.copy(alpha = 0.5f) else null,
        onClick = onTap
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = accentColor.copy(alpha = 0.5f),
                        topLeft = Offset(0f, 0f),
                        size = Size(3.dp.toPx(), size.height)
                    )
                }
                .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Count badge — color intensity scales with repeat count
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${count}x", color = accentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(PhoneFormatter.format(call.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                    Text(
                        stringResource(if (call.isCall) R.string.blocked_log_call else R.string.blocked_log_sms),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatSubtext
                    )
                }
                if (call.matchReason.isNotEmpty()) {
                    Text(call.matchReason.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = CatPeach)
                }
            }
            IconButton(onClick = onBlock) {
                Icon(Icons.Default.Block, stringResource(R.string.cd_block), tint = CatYellow)
            }
        }
    }
}
