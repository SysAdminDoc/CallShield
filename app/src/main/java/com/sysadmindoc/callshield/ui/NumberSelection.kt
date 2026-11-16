package com.sysadmindoc.callshield.ui

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.ShapeLg
import com.sysadmindoc.callshield.ui.theme.SurfaceBright
import com.sysadmindoc.callshield.ui.theme.hapticTick

internal const val NUMBER_SELECTION_BAR_TAG = "number_selection_bar"

/**
 * A row a long press selects. While any row is selected, a tap toggles the
 * row, and its own buttons, switch and swipe are out of reach.
 * [longPressSelects] is false for a card that takes its long press itself,
 * since a long press here would also let the card's tap through when the
 * finger lifts.
 */
@Composable
internal fun SelectableRow(
    label: String,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    longPressSelects: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectLabel = stringResource(R.string.blocklist_select_action)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            modifier.then(
                if (selecting || !longPressSelects) {
                    Modifier
                } else {
                    Modifier
                        // The row's buttons and switch take their own presses,
                        // so only the card itself starts a selection.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    hapticTick(context)
                                    currentOnSelect()
                                },
                            )
                        }
                        // One TalkBack stop per row; the swipe's Unblock action
                        // merges up because this node sets no actions of its own.
                        .semantics(mergeDescendants = true) {
                            onLongClick(label = selectLabel) {
                                currentOnSelect()
                                true
                            }
                        }
                },
            ),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                SelectionMark(selected)
                Spacer(Modifier.width(10.dp))
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .then(
                            when {
                                !selecting -> Modifier
                                selected -> Modifier.border(2.dp, CatGreen, shape).clearAndSetSemantics {}
                                else -> Modifier.clearAndSetSemantics {}
                            },
                        ),
            ) {
                content()
            }
        }
        if (selecting) {
            // Drawn over the row so a tap selects instead of reaching its controls.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { currentOnSelect() })
                        .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
internal fun SelectionMark(selected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(if (selected) Modifier.background(CatGreen) else Modifier.border(2.dp, CatSubtext, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** What the bar under picked calls can do to their numbers. */
internal enum class SelectionAction { BLOCK, REPORT, NOT_SPAM }

/** Block, Report and Not spam for the calls picked in Recent or the Blocked log. */
@Composable
internal fun NumberSelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(NUMBER_SELECTION_BAR_TAG),
        shape = RoundedCornerShape(ShapeLg),
        color = SurfaceBright,
        border = BorderStroke(1.dp, CatRed.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 10.dp, bottom = 10.dp)) {
            Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.dialog_cancel), color = CatSubtext, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    pluralStringResource(R.plurals.blocklist_selection_count, count, count),
                    // TalkBack reads the new count as calls are picked.
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    color = CatText,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PremiumActionButton(
                    label = stringResource(R.string.detail_block),
                    icon = Icons.Default.Block,
                    color = CatRed,
                    onClick = { onAction(SelectionAction.BLOCK) },
                    modifier = Modifier.weight(1f),
                )
                PremiumActionButton(
                    label = stringResource(R.string.detail_report),
                    icon = Icons.Default.Flag,
                    color = CatRed,
                    onClick = { onAction(SelectionAction.REPORT) },
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
                PremiumActionButton(
                    label = stringResource(R.string.detail_not_spam),
                    icon = Icons.Default.CheckCircle,
                    color = CatGreen,
                    onClick = { onAction(SelectionAction.NOT_SPAM) },
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
            }
        }
    }
}

/**
 * Runs [action] on every picked number and shows one snackbar for all of
 * them, with an Undo that takes back the whole batch. [onApplied] runs once
 * the batch is written, before the snackbar.
 */
internal suspend fun applySelectionAction(
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    resources: Resources,
    action: SelectionAction,
    numbers: Collection<String>,
    blockDescription: String,
    onApplied: () -> Unit = {},
) {
    val outcome =
        when (action) {
            SelectionAction.BLOCK -> viewModel.blockPickedNumbers(numbers, blockDescription)
            SelectionAction.REPORT -> viewModel.reportPickedNumbers(numbers)
            SelectionAction.NOT_SPAM -> viewModel.markPickedNotSpam(numbers)
        }
    onApplied()
    val message = selectionMessage(resources, action, outcome) ?: return
    snackbar.currentSnackbarData?.dismiss()
    val result =
        snackbar.showSnackbar(
            message = message,
            actionLabel = if (outcome.undoable) resources.getString(R.string.detail_undo) else null,
            // Not spam goes out five seconds after the tap, so its Undo can't outlast that.
            duration = if (action == SelectionAction.NOT_SPAM) SnackbarDuration.Short else SnackbarDuration.Long,
        )
    if (result == SnackbarResult.ActionPerformed) viewModel.undoSelection(outcome)
}

/** The batch's snackbar text, one line per kind of result, or null when nothing was picked. */
internal fun selectionMessage(
    resources: Resources,
    action: SelectionAction,
    outcome: MainViewModel.SelectionOutcome,
): String? {
    val doneText =
        when (action) {
            SelectionAction.BLOCK -> R.plurals.selection_blocked
            SelectionAction.REPORT -> R.plurals.selection_reported
            SelectionAction.NOT_SPAM -> R.plurals.selection_marked_not_spam
        }
    val failedText = if (action == SelectionAction.BLOCK) R.plurals.selection_not_blocked else R.plurals.selection_not_reported
    val lines =
        buildList {
            if (outcome.done > 0) add(resources.getQuantityString(doneText, outcome.done, outcome.done))
            if (outcome.alreadyReported > 0) {
                add(resources.getQuantityString(R.plurals.selection_already_reported, outcome.alreadyReported, outcome.alreadyReported))
            }
            if (outcome.failed > 0) add(resources.getQuantityString(failedText, outcome.failed, outcome.failed))
        }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
