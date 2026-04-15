package com.sysadmindoc.callshield.ui.screens.recent

import android.Manifest
import android.content.Context
import android.provider.CallLog
import androidx.compose.animation.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.net.Uri
import android.provider.ContactsContract
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
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
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var initialLoadCompleted by remember { mutableStateOf(false) }
    var filterMode by rememberSaveable { mutableIntStateOf(0) } // 0=All, 1=Incoming, 2=Outgoing, 3=Missed, 4=Spam
    var hasCallLogPermission by remember(context) {
        mutableStateOf(
            CallShieldPermissions.isPermissionGranted(
                context,
                Manifest.permission.READ_CALL_LOG
            )
        )
    }

    fun refreshRecentCalls(showSkeleton: Boolean) {
        if (loading || refreshing) return
        if (!hasCallLogPermission) {
            calls = emptyList()
            loading = false
            refreshing = false
            initialLoadCompleted = true
            return
        }

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

    LaunchedEffect(context.applicationContext, hasCallLogPermission) {
        if (hasCallLogPermission) {
            refreshRecentCallsState.value(true)
        } else {
            calls = emptyList()
            loading = false
            refreshing = false
            initialLoadCompleted = true
        }
    }

    DisposableEffect(lifecycleOwner, context.applicationContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCallLogPermission = CallShieldPermissions.isPermissionGranted(
                    context.applicationContext,
                    Manifest.permission.READ_CALL_LOG
                )
            }
            if (event == Lifecycle.Event.ON_RESUME && initialLoadCompleted && hasCallLogPermission) {
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
    val missedCount = calls.count {
        it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE
    }
    val incomingCount = calls.count { it.type == CallLog.Calls.INCOMING_TYPE }
    val outgoingCount = calls.count { it.type == CallLog.Calls.OUTGOING_TYPE }
    val contactCount = calls.count { it.contactName != null }
    val filterOptions = listOf(
        RecentFilterOption(
            mode = 0,
            label = stringResource(R.string.recent_filter_all, calls.size),
            color = CatGreen
        ),
        RecentFilterOption(
            mode = 1,
            label = stringResource(R.string.recent_filter_incoming, incomingCount),
            color = CatBlue
        ),
        RecentFilterOption(
            mode = 2,
            label = stringResource(R.string.recent_filter_outgoing, outgoingCount),
            color = CatTeal
        ),
        RecentFilterOption(
            mode = 3,
            label = stringResource(R.string.recent_filter_missed, missedCount),
            color = CatPeach
        ),
        RecentFilterOption(
            mode = 4,
            label = stringResource(R.string.recent_filter_spam, spamCount),
            color = CatRed
        )
    )

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasCallLogPermission) {
            RecentCallsPermissionState(
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )
        } else if (!loading) {
            RecentCallsSummaryCard(
                totalCount = calls.size,
                spamCount = spamCount,
                missedCount = missedCount,
                contactCount = contactCount,
                refreshing = refreshing,
                onRefresh = { refreshRecentCalls(false) }
            )
            if (calls.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filterOptions.size) { index ->
                        val option = filterOptions[index]
                        FilterChip(
                            selected = filterMode == option.mode,
                            onClick = { filterMode = option.mode },
                            label = { Text(option.label) },
                            border = BorderStroke(
                                1.dp,
                                if (filterMode == option.mode) {
                                    option.color.copy(alpha = 0.35f)
                                } else {
                                    CatMuted.copy(alpha = 0.3f)
                                }
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = option.color.copy(alpha = 0.18f),
                                selectedLabelColor = option.color
                            )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (hasCallLogPermission && loading) {
            // Premium shimmer skeleton while loading
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(8) { SkeletonListItem(modifier = Modifier.fillMaxWidth()) }
            }
        } else if (hasCallLogPermission && filtered.isEmpty()) {
            RecentEmptyStateCard(
                title = if (filterMode == 0) {
                    stringResource(R.string.recent_no_calls)
                } else {
                    stringResource(R.string.recent_no_matching)
                },
                subtitle = if (filterMode == 0) {
                    stringResource(R.string.recent_no_calls_desc)
                } else {
                    stringResource(R.string.recent_no_matching_desc)
                },
                accentColor = if (filterMode == 0) CatBlue else CatPeach,
                actionLabel = if (filterMode == 0) null else stringResource(R.string.recent_show_all),
                onAction = if (filterMode == 0) null else { { filterMode = 0 } }
            )
        } else if (hasCallLogPermission) {
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
                        RecentCallItem(
                            call = call,
                            onOpenDetail = { viewModel.openNumberDetail(call.number) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentCallItem(call: RecentCall, onOpenDetail: () -> Unit) {
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
        onClick = onOpenDetail,
        cornerRadius = 14.dp,
        accentColor = if (call.isSpam) CatRed else null,
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
                        if (call.isSpam) {
                            Text(
                                call.spamReason.replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = CatRed
                            )
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
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    GradientDivider(modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val digits = call.number.filter { it.isDigit() }
                        RecentActionButton(
                            icon = Icons.Default.Search,
                            label = stringResource(R.string.recent_google),
                            color = CatBlue
                        ) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        "https://www.google.com/search?q=${
                                            android.net.Uri.encode("$digits phone number spam")
                                        }"
                                    )
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                        RecentActionButton(
                            icon = Icons.Default.ContentCopy,
                            label = stringResource(R.string.recent_copy),
                            color = CatSubtext
                        ) {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Phone", call.number))
                            Toast.makeText(
                                context,
                                context.getString(R.string.recent_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        RecentActionButton(
                            icon = Icons.Default.Info,
                            label = stringResource(R.string.recent_detail),
                            color = CatMauve
                        ) { onOpenDetail() }
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

private data class RecentFilterOption(
    val mode: Int,
    val label: String,
    val color: Color,
)

@Composable
private fun RecentCallsSummaryCard(
    totalCount: Int,
    spamCount: Int,
    missedCount: Int,
    contactCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val formatter = remember { NumberFormat.getIntegerInstance() }
    PremiumCard(accentColor = CatBlue, modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(stringResource(R.string.recent_summary_title), CatBlue)
                    Text(
                        stringResource(R.string.recent_summary_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext
                    )
                    StatusPill(
                        text = if (refreshing) {
                            stringResource(R.string.recent_summary_refreshing)
                        } else {
                            stringResource(R.string.recent_summary_live)
                        },
                        color = if (refreshing) CatPeach else CatBlue,
                        horizontalPadding = 10.dp,
                        verticalPadding = 6.dp,
                        textStyle = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = CatBlue
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_recent),
                            tint = CatBlue
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecentSummaryPill(
                    value = formatter.format(totalCount),
                    label = stringResource(R.string.recent_summary_total),
                    color = CatBlue,
                    modifier = Modifier.weight(1f)
                )
                RecentSummaryPill(
                    value = formatter.format(spamCount),
                    label = stringResource(R.string.recent_summary_spam),
                    color = CatRed,
                    modifier = Modifier.weight(1f)
                )
                RecentSummaryPill(
                    value = formatter.format(missedCount),
                    label = stringResource(R.string.recent_summary_missed),
                    color = CatPeach,
                    modifier = Modifier.weight(1f)
                )
                RecentSummaryPill(
                    value = formatter.format(contactCount),
                    label = stringResource(R.string.recent_summary_known),
                    color = CatGreen,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RecentSummaryPill(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext
            )
        }
    }
}

@Composable
private fun RecentCallsPermissionState(
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        PremiumCard(accentColor = CatPeach, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(stringResource(R.string.recent_permission_title), CatPeach)
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = CatPeach,
                    modifier = Modifier
                        .size(40.dp)
                        .accentGlow(CatPeach, radius = 160f, alpha = 0.08f)
                )
                Text(
                    stringResource(R.string.recent_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = CatText
                )
                Text(
                    stringResource(R.string.recent_permission_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                GradientDivider(color = CatPeach)
                RecentGuidanceRow(
                    icon = Icons.Default.PrivacyTip,
                    title = stringResource(R.string.recent_permission_hint_private_title),
                    subtitle = stringResource(R.string.recent_permission_hint_private_body),
                    accentColor = CatBlue
                )
                RecentGuidanceRow(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.recent_permission_hint_recovery_title),
                    subtitle = stringResource(R.string.recent_permission_hint_recovery_body),
                    accentColor = CatPeach
                )
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = CatPeach),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.recent_permission_cta),
                        color = Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentEmptyStateCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        PremiumCard(accentColor = accentColor, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PhoneMissed,
                    contentDescription = stringResource(R.string.cd_no_recent_calls),
                    tint = accentColor,
                    modifier = Modifier
                        .size(48.dp)
                        .accentGlow(color = accentColor, radius = 140f, alpha = 0.10f)
                )
                Text(title, color = CatText, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = CatSubtext,
                    style = MaterialTheme.typography.bodySmall
                )
                if (actionLabel != null && onAction != null) {
                    OutlinedButton(
                        onClick = onAction,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f))
                    ) {
                        Text(actionLabel, color = accentColor, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentGuidanceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = CatText, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
    }
}
