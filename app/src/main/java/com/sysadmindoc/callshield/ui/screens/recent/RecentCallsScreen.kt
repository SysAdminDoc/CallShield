package com.sysadmindoc.callshield.ui.screens.recent

import android.content.Context
import android.provider.CallLog
import androidx.compose.animation.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import android.net.Uri
import android.provider.ContactsContract
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
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
    var calls by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        calls = loadRecentCalls(context)
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CatGreen)
            }
        } else if (calls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhoneMissed, null, tint = CatOverlay, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No recent calls", color = CatSubtext)
                    Text("Call log is empty or permission denied", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(calls) { index, call ->
                    val visible = remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
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
        CallLog.Calls.INCOMING_TYPE -> Icons.Default.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.Default.CallMade
        CallLog.Calls.MISSED_TYPE -> Icons.Default.PhoneMissed
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

    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (call.isSpam) CatRed.copy(alpha = 0.08f) else SurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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

            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
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

@Composable
fun RecentActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
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
            var count = 0
            while (c.moveToNext() && count < 100) {
                val number = c.getString(numIdx) ?: continue
                val clean = number.filter { it.isDigit() || it == '+' }
                if (clean.length < 5) continue
                val spamResult = repo.isSpam(clean)
                val contactName = try {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(clean))
                    val cc = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    cc?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: Exception) { null }
                calls.add(RecentCall(
                    number = clean,
                    type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                    date = if (dateIdx >= 0) c.getLong(dateIdx) else 0,
                    duration = if (durIdx >= 0) c.getInt(durIdx) else 0,
                    isSpam = spamResult.isSpam,
                    spamReason = spamResult.matchSource,
                    contactName = contactName
                ))
                count++
            }
        }
    } catch (_: SecurityException) {}
    calls
}
