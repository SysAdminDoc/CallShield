package com.sysadmindoc.callshield.ui.screens.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SmsBodyRedactor
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.TemporaryDecisionDuration
import com.sysadmindoc.callshield.ui.TemporaryDecisionMenu
import com.sysadmindoc.callshield.ui.accessibleSwipeActions
import com.sysadmindoc.callshield.ui.blockReasonAccessibilityLabelRes
import com.sysadmindoc.callshield.ui.expandableStateSemantics
import com.sysadmindoc.callshield.ui.friendlyMatchReasonLabel
import com.sysadmindoc.callshield.ui.rememberTemporaryDecisionDurations
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.launchViewUrlSafely
import com.sysadmindoc.callshield.util.localizedDateTimeFormat
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BlockedLogScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val blockedCalls by viewModel.blockedCalls.collectAsStateWithLifecycle()
    var filterMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedReasonCodeWire by rememberSaveable { mutableStateOf("") }
    var showReasonMenu by rememberSaveable { mutableStateOf(false) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val selectedReasonCode = BlockReasonCode.fromStored(selectedReasonCodeWire).takeIf { selectedReasonCodeWire.isNotBlank() }
    val availableReasonCodes =
        remember(blockedCalls) {
            blockedCalls
                .asSequence()
                .map { it.reasonCode }
                .filterNot { it == BlockReasonCode.UNKNOWN }
                .distinct()
                .sortedBy { it.wireValue }
                .toList()
        }

    val filtered =
        blockedCalls.filter { call ->
            val mediaMatches =
                when (filterMode) {
                    1 -> call.isCall
                    2 -> !call.isCall
                    else -> true
                }
            mediaMatches && (selectedReasonCode == null || call.reasonCode == selectedReasonCode)
        }

    // Grouped view: collapse by number
    val groupedList =
        if (grouped) {
            filtered
                .groupBy { it.number }
                .map { (number, calls) ->
                    calls.first().copy() to calls.size
                }.sortedByDescending { it.second }
        } else {
            null
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = Black,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filterMode == 0,
                    onClick = { filterMode = 0 },
                    label = { Text(stringResource(R.string.blocked_log_filter_all, blockedCalls.size)) },
                    shape = RoundedCornerShape(8.dp),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SurfaceBright,
                            selectedLabelColor = CatGreen,
                            containerColor = Color.Transparent,
                            labelColor = CatSubtext,
                        ),
                    border = null,
                )
                FilterChip(
                    selected = filterMode == 1,
                    onClick = { filterMode = 1 },
                    label = { Text(stringResource(R.string.blocked_log_filter_calls)) },
                    shape = RoundedCornerShape(8.dp),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SurfaceBright,
                            selectedLabelColor = CatGreen,
                            containerColor = Color.Transparent,
                            labelColor = CatSubtext,
                        ),
                    border = null,
                )
                FilterChip(
                    selected = filterMode == 2,
                    onClick = { filterMode = 2 },
                    label = { Text(stringResource(R.string.blocked_log_filter_sms)) },
                    shape = RoundedCornerShape(8.dp),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SurfaceBright,
                            selectedLabelColor = CatGreen,
                            containerColor = Color.Transparent,
                            labelColor = CatSubtext,
                        ),
                    border = null,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grouped = !grouped }) {
                    Icon(
                        if (grouped) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GroupWork,
                        contentDescription = stringResource(if (grouped) R.string.cd_ungroup else R.string.cd_group),
                        tint = if (grouped) CatYellow else CatOverlay,
                    )
                }
                if (blockedCalls.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.cd_clear_log), tint = CatRed)
                    }
                }
            }

            if (availableReasonCodes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        FilterChip(
                            selected = selectedReasonCode != null,
                            onClick = { showReasonMenu = true },
                            label = {
                                Text(
                                    selectedReasonCode?.let { friendlyMatchReasonLabel(it.wireValue) }
                                        ?: stringResource(R.string.blocked_log_filter_reason),
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SurfaceBright,
                                    selectedLabelColor = CatGreen,
                                    containerColor = Color.Transparent,
                                    labelColor = CatSubtext,
                                ),
                            border = null,
                        )
                        DropdownMenu(
                            expanded = showReasonMenu,
                            onDismissRequest = { showReasonMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.blocked_log_filter_all_reasons)) },
                                onClick = {
                                    selectedReasonCodeWire = ""
                                    showReasonMenu = false
                                },
                            )
                            availableReasonCodes.forEach { reasonCode ->
                                DropdownMenuItem(
                                    text = { Text(friendlyMatchReasonLabel(reasonCode.wireValue)) },
                                    onClick = {
                                        selectedReasonCodeWire = reasonCode.wireValue
                                        showReasonMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                BlockedLogEmptyState(
                    title =
                        if (blockedCalls.isEmpty()) {
                            stringResource(R.string.blocked_log_empty_all_title)
                        } else {
                            stringResource(R.string.blocked_log_empty_filter_title)
                        },
                    subtitle =
                        if (blockedCalls.isEmpty()) {
                            stringResource(R.string.blocked_log_empty_all_body)
                        } else {
                            stringResource(R.string.blocked_log_empty_filter_body)
                        },
                    accentColor = if (blockedCalls.isEmpty()) CatGreen else CatPeach,
                    actionLabel = if (blockedCalls.isEmpty()) null else stringResource(R.string.blocked_log_show_all),
                    onAction =
                        if (blockedCalls.isEmpty()) {
                            null
                        } else {
                            {
                                filterMode = 0
                                selectedReasonCodeWire = ""
                            }
                        },
                )
            } else if (grouped && groupedList != null) {
                // Grouped view
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = groupedList,
                        key = { _, item -> item.first.number },
                    ) { _, (call, count) ->
                        GroupedCallItem(
                            call = call,
                            count = count,
                            onTap = { viewModel.openNumberDetail(call.number) },
                            onBlock = { viewModel.blockNumber(call.number) },
                        )
                    }
                }
            } else {
                // Swipe-to-dismiss list
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(filtered, key = { _, call -> call.id }) { index, call ->
                        // rememberSaveable (keyed by the item's stable id via the
                        // LazyColumn saveable registry) keeps the entrance animation
                        // from replaying every time a row scrolls off-screen and back.
                        var visible by rememberSaveable(call.id) { mutableStateOf(false) }
                        val deletedMessage = stringResource(R.string.blocked_log_deleted)
                        val undoLabel = stringResource(R.string.blocked_log_undo)
                        val blockedFromSwipeDescription = stringResource(R.string.desc_blocked_from_log_swipe)
                        val blockedMessage =
                            stringResource(
                                R.string.blocked_log_number_blocked,
                                PhoneFormatter.formatIsolated(call.number),
                            )
                        val deleteActionLabel = stringResource(R.string.blocked_log_action_delete)
                        val blockActionLabel = stringResource(R.string.blocked_log_action_block)
                        LaunchedEffect(call.id) {
                            kotlinx.coroutines.delay(index.toLong().coerceAtMost(15) * 30)
                            visible = true
                        }
                        AnimatedVisibility(visible = visible, enter = slideInVertically { 40 } + fadeIn()) {
                            val dismissState = rememberSwipeToDismissBoxState()
                            var actionHandled by remember(call.id) { mutableStateOf(false) }

                            fun deleteEntryWithUndo() {
                                viewModel.deleteLogEntry(call)
                                hapticTick(context)
                                scope.launch {
                                    val result =
                                        snackbarHost.showSnackbar(
                                            message = deletedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short,
                                        )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreLogEntry(call)
                                    }
                                }
                            }

                            fun blockEntryWithUndo() {
                                viewModel.blockNumber(call.number, "spam", blockedFromSwipeDescription)
                                hapticConfirm(context)
                                scope.launch {
                                    // Offer Undo for assistive actions as well as the gesture.
                                    val result =
                                        snackbarHost.showSnackbar(
                                            message = blockedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short,
                                        )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.unblockByNumber(call.number)
                                    }
                                }
                            }

                            val swipeActions =
                                listOf(
                                    CustomAccessibilityAction(deleteActionLabel) {
                                        deleteEntryWithUndo()
                                        true
                                    },
                                    CustomAccessibilityAction(blockActionLabel) {
                                        blockEntryWithUndo()
                                        true
                                    },
                                )
                            LaunchedEffect(dismissState.currentValue) {
                                when (dismissState.currentValue) {
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        if (actionHandled) return@LaunchedEffect
                                        actionHandled = true
                                        // Clear the dismissed value before the row
                                        // leaves the list. The state is saveable and
                                        // keyed by call.id, so Undo — which re-inserts
                                        // the same id — would otherwise restore an
                                        // already-dismissed state and delete the entry
                                        // again. snapTo (not reset) because disposal
                                        // cancels an in-flight animation.
                                        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                        deleteEntryWithUndo()
                                    }

                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        if (actionHandled) return@LaunchedEffect
                                        actionHandled = true
                                        // Offer Undo — a permanent block is the more consequential
                                        // swipe and one accidental right-swipe otherwise sticks.
                                        blockEntryWithUndo()
                                        dismissState.reset()
                                    }

                                    SwipeToDismissBoxValue.Settled -> {
                                        actionHandled = false
                                    }
                                }
                            }
                            SwipeToDismissBox(
                                state = dismissState,
                                modifier = Modifier.accessibleSwipeActions(swipeActions),
                                backgroundContent = {
                                    val direction = dismissState.dismissDirection
                                    val color =
                                        when (direction) {
                                            SwipeToDismissBoxValue.StartToEnd -> CatYellow.copy(alpha = 0.3f)
                                            SwipeToDismissBoxValue.EndToStart -> CatRed.copy(alpha = 0.3f)
                                            else -> SurfaceBright
                                        }
                                    val icon =
                                        when (direction) {
                                            SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Block
                                            SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                                            else -> Icons.Default.Block
                                        }
                                    val align =
                                        when (direction) {
                                            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                            else -> Alignment.CenterEnd
                                        }
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(color)
                                                .padding(horizontal = 20.dp),
                                        contentAlignment = align,
                                    ) {
                                        Icon(icon, null, tint = CatText)
                                    }
                                },
                            ) {
                                val allowReason = stringResource(R.string.blocked_log_temporary_allow_reason)
                                val blockReason = stringResource(R.string.blocked_log_temporary_block_reason)
                                BlockedCallItem(
                                    call = call,
                                    onTap = { viewModel.openNumberDetail(call.number) },
                                    onTemporaryAllow = { duration ->
                                        viewModel.temporaryAllowNumber(
                                            call.number,
                                            duration.durationMillis,
                                            allowReason,
                                        )
                                        hapticConfirm(context)
                                        scope.launch {
                                            snackbarHost.showSnackbar(
                                                resources.getString(
                                                    R.string.temporary_decision_allowed,
                                                    PhoneFormatter.formatIsolated(call.number),
                                                    duration.label,
                                                ),
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onTemporaryBlock = { duration ->
                                        viewModel.temporaryBlockNumber(
                                            call.number,
                                            duration.durationMillis,
                                            "spam",
                                            blockReason,
                                        )
                                        hapticConfirm(context)
                                        scope.launch {
                                            snackbarHost.showSnackbar(
                                                resources.getString(
                                                    R.string.temporary_decision_blocked,
                                                    PhoneFormatter.formatIsolated(call.number),
                                                    duration.label,
                                                ),
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Clear log confirmation dialog
    if (showClearDialog) {
        val logClearedMessage = stringResource(R.string.blocked_log_log_cleared)
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.DeleteSweep, null, tint = CatRed, modifier = Modifier.size(32.dp)) },
            title = { Text(stringResource(R.string.blocked_log_clear_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.blocked_log_clear_message,
                        blockedCalls.size,
                        blockedCalls.size,
                    ),
                    color = CatSubtext,
                )
            },
            confirmButton = {
                PremiumActionButton(
                    label = stringResource(R.string.blocked_log_clear_all),
                    icon = Icons.Default.DeleteSweep,
                    color = CatRed,
                    onClick = {
                        viewModel.clearLog()
                        hapticConfirm(context)
                        showClearDialog = false
                        scope.launch {
                            snackbarHost.showSnackbar(
                                logClearedMessage,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.blocked_log_cancel), color = CatSubtext)
                }
            },
        )
    }
}

@Composable
private fun BlockedLogEmptyState(
    title: String,
    subtitle: String,
    accentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.cd_no_items),
                tint = accentColor,
                modifier = Modifier.size(42.dp),
            )
            Text(title, color = CatText, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = CatSubtext,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                PremiumCompactButton(
                    label = actionLabel,
                    icon = Icons.Default.Refresh,
                    color = accentColor,
                    onClick = onAction,
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BlockedCallItem(
    call: BlockedCall,
    onTap: () -> Unit,
    onTemporaryAllow: (TemporaryDecisionDuration) -> Unit,
    onTemporaryBlock: (TemporaryDecisionDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dateFormat = remember(context) { localizedDateTimeFormat(context) }
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val temporaryDurations = rememberTemporaryDecisionDurations()
    val copiedMessage = stringResource(R.string.blocked_log_copied, PhoneFormatter.formatIsolated(call.number))
    val copiedShortMessage = stringResource(R.string.blocked_log_copied_short)
    val clipLabelPhone = stringResource(R.string.clip_label_phone)
    val clipLabelPhoneNumber = stringResource(R.string.clip_label_phone_number)
    val expandedStateDescription = stringResource(R.string.accessibility_state_expanded)
    val collapsedStateDescription = stringResource(R.string.accessibility_state_collapsed)

    PremiumCard(
        cornerRadius = 12.dp,
        modifier =
            modifier.combinedClickable(
                onClick = onTap,
                onLongClick = {
                    val clip = ClipData.newPlainText(clipLabelPhoneNumber, call.number)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    Toast
                        .makeText(
                            context,
                            copiedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                },
            ),
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector =
                        if (call.isCall) {
                            if (call.wasBlocked) Icons.Default.PhoneDisabled else Icons.Default.Phone
                        } else {
                            if (call.wasBlocked) Icons.Default.SpeakerNotesOff else Icons.Default.MarkEmailRead
                        },
                    contentDescription =
                        stringResource(
                            if (call.wasBlocked) {
                                if (call.isCall) R.string.blocked_log_blocked_call else R.string.blocked_log_blocked_sms
                            } else {
                                R.string.blocked_log_safety_exempted
                            },
                        ),
                    tint =
                        if (call.wasBlocked) {
                            if (call.isCall) CatRed else CatMauve
                        } else {
                            CatGreen
                        },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(PhoneFormatter.formatIsolated(call.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dateFormat.format(Date(call.timestamp)), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                        if (location != null) Text(location, style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                    }
                    if (call.reasonCode != BlockReasonCode.UNKNOWN) {
                        val reasonSource =
                            if (call.reasonCode == BlockReasonCode.CATEGORY_POLICY) call.matchReason else call.reasonCode.wireValue
                        val categoryPolicy =
                            remember(call.reasonCode, call.matchReason) {
                                com.sysadmindoc.callshield.data.CategoryCallPolicy
                                    .parseMatchSource(reasonSource)
                            }
                        val reasonText =
                            if (categoryPolicy == null) {
                                friendlyMatchReasonLabel(call.reasonCode.wireValue)
                            } else {
                                stringResource(
                                    R.string.detail_category_action_source,
                                    stringResource(categoryPolicy.category.stringResId),
                                    stringResource(categoryPolicy.action.labelResId),
                                )
                            }
                        val confidenceText =
                            if (call.confidence < 100) {
                                stringResource(R.string.confidence_suffix, call.confidence)
                            } else {
                                ""
                            }
                        // Feature A: prepend the resolved CallCategory label.
                        // Falls back silently to just the raw reason if the
                        // resolver lands on Unknown — no noise, no mislabels.
                        val category =
                            remember(call.reasonCode, reasonSource, call.type, call.confidence) {
                                com.sysadmindoc.callshield.data.CallCategoryResolver.resolveFromLog(
                                    matchReason = reasonSource,
                                    type = call.type,
                                    description = "",
                                    confidence = call.confidence,
                                )
                            }
                        val label =
                            if (category != com.sysadmindoc.callshield.data.CallCategory.Unknown) {
                                "${category.emoji} ${stringResource(category.stringResId)} · $reasonText$confidenceText"
                            } else {
                                "$reasonText$confidenceText"
                            }
                        val accessibilityReason = stringResource(blockReasonAccessibilityLabelRes(call.reasonCode))
                        Text(
                            label,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = accessibilityReason
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = CatPeach,
                        )
                    }
                    val redactedSmsBody =
                        remember(call.smsBody) {
                            SmsBodyRedactor.redactForPreview(call.smsBody)
                        }
                    if (redactedSmsBody != null) {
                        Text(
                            redactedSmsBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext,
                            maxLines = 2,
                        )
                    }
                    call.pipelineDiagnostic?.takeIf(String::isNotBlank)?.let { diagnostic ->
                        Text(
                            stringResource(R.string.blocked_log_pipeline_diagnostic, diagnostic),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatRed,
                        )
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier =
                        Modifier.expandableStateSemantics(
                            expanded = expanded,
                            expandedStateDescription = expandedStateDescription,
                            collapsedStateDescription = collapsedStateDescription,
                            onExpandedChange = { expanded = it },
                        ),
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription =
                            stringResource(
                                if (expanded) R.string.cd_collapse else R.string.cd_expand,
                            ),
                        tint = CatOverlay,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Expandable action buttons
            AnimatedVisibility(visible = expanded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val digits = filterAsciiDigits(call.number)
                    // Search Google
                    SmallActionButton(Icons.Default.Search, stringResource(R.string.blocked_log_google), CatBlue) {
                        val url = "https://www.google.com/search?q=${Uri.encode("$digits phone number spam")}"
                        context.launchViewUrlSafely(url)
                    }
                    // Check databases (open number detail)
                    SmallActionButton(Icons.Default.Storage, stringResource(R.string.blocked_log_databases), CatGreen) { onTap() }
                    // Copy
                    SmallActionButton(Icons.Default.ContentCopy, stringResource(R.string.blocked_log_copy), CatSubtext) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(clipLabelPhone, call.number))
                        Toast.makeText(context, copiedShortMessage, Toast.LENGTH_SHORT).show()
                    }
                    // Detail
                    SmallActionButton(Icons.Default.Info, stringResource(R.string.blocked_log_detail), CatMauve) { onTap() }
                    TemporaryDecisionMenu(
                        label = stringResource(R.string.temporary_decision_allow),
                        icon = Icons.Default.CheckCircle,
                        color = CatGreen,
                        durations = temporaryDurations,
                        onSelect = onTemporaryAllow,
                    )
                    TemporaryDecisionMenu(
                        label = stringResource(R.string.temporary_decision_block),
                        icon = Icons.Default.Block,
                        color = CatYellow,
                        durations = temporaryDurations,
                        onSelect = onTemporaryBlock,
                    )
                }
            }
        }
    }
}

@Composable
fun SmallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    PremiumCompactButton(label = label, icon = icon, color = color, onClick = onClick)
}

@Suppress("LongMethod")
@Composable
fun GroupedCallItem(
    call: BlockedCall,
    count: Int,
    onTap: () -> Unit,
    onBlock: () -> Unit,
) {
    val location = remember(call.number) { AreaCodeLookup.lookup(call.number) }

    val accentColor =
        if (count >= 5) {
            CatRed
        } else if (count >= 3) {
            CatPeach
        } else {
            CatYellow
        }

    PremiumCard(
        cornerRadius = 12.dp,
        accentColor = if (count >= 5) CatRed.copy(alpha = 0.5f) else null,
        onClick = onTap,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            color = accentColor.copy(alpha = 0.5f),
                            topLeft = Offset(0f, 0f),
                            size = Size(3.dp.toPx(), size.height),
                        )
                    }.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Count badge — color intensity scales with repeat count. Square-ish
            // rounded backdrop (10.dp) deliberately avoids a pill/full-circle
            // shape for text-bearing badges.
            val badgeShape = RoundedCornerShape(10.dp)
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(badgeShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)), badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${count}x", color = accentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(PhoneFormatter.formatIsolated(call.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                    Text(
                        stringResource(if (call.isCall) R.string.blocked_log_call else R.string.blocked_log_sms),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatSubtext,
                    )
                }
                if (call.reasonCode != BlockReasonCode.UNKNOWN) {
                    val accessibilityReason = stringResource(blockReasonAccessibilityLabelRes(call.reasonCode))
                    Text(
                        friendlyMatchReasonLabel(call.reasonCode.wireValue),
                        modifier =
                            Modifier.semantics {
                                contentDescription = accessibilityReason
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach,
                    )
                }
            }
            IconButton(onClick = onBlock) {
                Icon(Icons.Default.Block, stringResource(R.string.cd_block), tint = CatYellow)
            }
        }
    }
}
