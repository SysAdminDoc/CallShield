package com.sysadmindoc.callshield.ui.screens.recent

import android.content.Context
import android.provider.CallLog
import androidx.compose.animation.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.net.Uri
import android.provider.ContactsContract
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class RecentCall(
    val number: String,
    val type: Int, // CallLog.Calls.INCOMING_TYPE, etc.
    val date: Long,
    val duration: Int,
    val isSpam: Boolean = false,
    val spamReason: String = "",
    val contactName: String? = null
)

@Composable
fun RecentCallsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var calls by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var initialLoadCompleted by remember { mutableStateOf(false) }
    var filterMode by rememberSaveable { mutableIntStateOf(0) } // 0=All, 1=Incoming, 2=Outgoing, 3=Missed, 4=Spam

    fun refreshRecentCalls(showSkeleton: Boolean) {
        if (loading || refreshing) return

        scope.launch {
            if (showSkeleton) {
                loading = true
            } else {
                refreshing = true
            }

            try {
                calls = loadRecentCalls(context.applicationContext)
                initialLoadCompleted = true
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    val refreshRecentCallsState = rememberUpdatedState(::refreshRecentCalls)

    LaunchedEffect(context.applicationContext) {
        refreshRecentCallsState.value(true)
    }

    DisposableEffect(lifecycleOwner, context.applicationContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && initialLoadCompleted) {
                refreshRecentCallsState.value(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filtered = when (filterMode) {
        1 -> calls.filter { it.type == CallLog.Calls.INCOMING_TYPE }
        2 -> calls.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
        3 -> calls.filter { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
        4 -> calls.filter { it.isSpam }
        else -> calls
    }
    val spamCount = calls.count { it.isSpam }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        if (!loading && calls.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = filterMode == 0, onClick = { filterMode = 0 }, label = { Text("All (${calls.size})") },
                    border = BorderStroke(1.dp, if (filterMode == 0) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen))
                FilterChip(selected = filterMode == 3, onClick = { filterMode = 3 }, label = { Text("Missed") },
                    border = BorderStroke(1.dp, if (filterMode == 3) CatPeach.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatPeach.copy(alpha = 0.2f), selectedLabelColor = CatPeach))
                if (spamCount > 0) {
                    FilterChip(selected = filterMode == 4, onClick = { filterMode = 4 }, label = { Text("Spam ($spamCount)") },
                        border = BorderStroke(1.dp, if (filterMode == 4) CatRed.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatRed.copy(alpha = 0.2f), selectedLabelColor = CatRed))
                }
                IconButton(
                    onClick = { refreshRecentCalls(calls.isEmpty()) },
                    enabled = !refreshing
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = CatBlue
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh recent calls", tint = CatOverlay)
                    }
                }
            }
        }

        if (loading) {
            // Premium shimmer skeleton while loading
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(8) { SkeletonListItem(modifier = Modifier.fillMaxWidth()) }
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.PhoneMissed,
                        null,
                        tint = CatOverlay,
                        modifier = Modifier
                            .size(64.dp)
                            .accentGlow(color = CatOverlay, radius = 120f, alpha = 0.10f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (filterMode == 0) "No recent calls" else "No matching calls",
                        color = CatSubtext, style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (filterMode == 0) "Call log is empty or permission denied" else "Try a different filter",
                        color = CatOverlay, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = filtered,
                    key = { _, call -> "${call.number}|${call.date}|${call.type}" }
                ) { index, call ->
                    val visible = remember(call.number, call.date) { mutableStateOf(false) }
                    LaunchedEffect(call.number, call.date) {
                        kotlinx.coroutines.delay(index.toLong().coerceAtMost(20) * 25)
                        visible.value = true
                    }
                    AnimatedVisibility(visible = visible.value, enter = slideInVertically { 30 } + fadeIn()) {
                        RecentCallItem(call = call, onClick = { viewModel.openNumberDetail(call.number) })
                    }
                }
            }
        }
    }
}

@Composable
fun RecentCallItem(call: RecentCall, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }

    val typeIcon = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
        CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.PhoneMissed
        CallLog.Calls.REJECTED_TYPE -> Icons.Default.CallEnd
        else -> Icons.Default.Phone
    }
    val typeColor = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> CatGreen
        CallLog.Calls.OUTGOING_TYPE -> CatBlue
        CallLog.Calls.MISSED_TYPE -> CatRed
        CallLog.Calls.REJECTED_TYPE -> CatPeach
        else -> CatSubtext
    }

    // Left accent bar color: calls get CatBlue, SMS-related types could be CatMauve
    // Since RecentCall represents call log entries, we use CatBlue for calls
    // and CatMauve if it were an SMS. Here type is call log type, so we differentiate
    // by spam status for visual interest, but per spec: calls=CatBlue, SMS=CatMauve.
    // Call log entries are always calls, so we use CatBlue as default, CatRed for spam.
    val accentBarColor = when {
        call.isSpam -> CatRed
        else -> CatBlue
    }

    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    PremiumCard(
        cornerRadius = 14.dp,
        accentColor = if (call.isSpam) CatRed else null,
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            // Draw a subtle 3dp accent bar on the left side
                            drawRect(
                                color = accentBarColor.copy(alpha = 0.5f),
                                topLeft = Offset(0f, 0f),
                                size = Size(3.dp.toPx(), size.height)
                            )
                        }
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val riskColor = when {
                        call.contactName != null -> CatGreen
                        call.isSpam -> CatRed
                        else -> CatYellow
                    }
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(riskColor))
                    Spacer(Modifier.width(8.dp))
                    Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (call.contactName != null) {
                                    Text(call.contactName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = CatGreen)
                                }
                                Text(PhoneFormatter.format(call.number), fontWeight = if (call.contactName == null) FontWeight.SemiBold else FontWeight.Normal, style = if (call.contactName == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
                            }
                            if (call.isSpam) {
                                Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(14.dp))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(dateFormat.format(Date(call.date)), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                            if (call.duration > 0) Text("${call.duration}s", style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                        }
                        if (location != null) Text(location, style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                        if (call.isSpam) Text(call.spamReason.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = CatRed)
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Expand", tint = CatOverlay, modifier = Modifier.size(20.dp))
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    GradientDivider(modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val digits = call.number.filter { it.isDigit() }
                        RecentActionButton(Icons.Default.Search, "Google", CatBlue) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode("$digits phone number spam")}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        RecentActionButton(Icons.Default.Storage, "Databases", CatGreen) { onClick() }
                        RecentActionButton(Icons.Default.ContentCopy, "Copy", CatSubtext) {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Phone", call.number))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        RecentActionButton(Icons.Default.Info, "Detail", CatMauve) { onClick() }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

private suspend fun loadRecentCalls(context: Context): List<RecentCall> = withContext(Dispatchers.IO) {
    val repo = SpamRepository.getInstance(context)
    val calls = mutableListOf<RecentCall>()
    try {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use { c ->
            val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            if (numIdx < 0) return@use

            // First pass: collect raw call log entries
            data class RawCall(val number: String, val type: Int, val date: Long, val duration: Int)
            val rawCalls = mutableListOf<RawCall>()
            while (c.moveToNext() && rawCalls.size < 100) {
                val number = c.getString(numIdx) ?: continue
                val clean = number.filter { it.isDigit() || it == '+' }
                if (clean.length < 5) continue
                rawCalls.add(RawCall(
                    number = clean,
                    type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                    date = if (dateIdx >= 0) c.getLong(dateIdx) else 0,
                    duration = if (durIdx >= 0) c.getInt(durIdx) else 0,
                ))
            }

            // Batch spam check: only check unique numbers once
            val uniqueNumbers = rawCalls.map { it.number }.distinct()
            val spamCache = uniqueNumbers.associateWith { repo.isSpam(it, realtimeCall = false) }

            // Batch contact lookup
            val contactCache = mutableMapOf<String, String?>()
            for (num in uniqueNumbers) {
                if (num in contactCache) continue
                contactCache[num] = try {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
                    val cc = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    cc?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: Exception) { null }
            }

            // Build final list
            for (raw in rawCalls) {
                val spamResult = spamCache[raw.number]
                calls.add(RecentCall(
                    number = raw.number,
                    type = raw.type,
                    date = raw.date,
                    duration = raw.duration,
                    isSpam = spamResult?.isSpam ?: false,
                    spamReason = spamResult?.matchSource ?: "",
                    contactName = contactCache[raw.number]
                ))
            }
        }
    } catch (_: SecurityException) {}
    calls
}
