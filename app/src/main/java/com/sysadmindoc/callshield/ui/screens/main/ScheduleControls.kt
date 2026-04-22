package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.ui.screens.settings.HourPicker
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.StatusPill

/**
 * Small state bundle captured from [ScheduleSection]. Rule-add dialogs
 * pass one of these into [ScheduleSection] and receive updates via
 * [onChange]; when the user commits the dialog, they call [toSchedule]
 * (or pass [TimeSchedule()] when [enabled] is false) to persist the rule.
 *
 * Kept here rather than inside each dialog so all three rule-add dialogs
 * (AddWildcardDialog, AddHashWildcardDialog, AddKeywordDialog) share the
 * same UX contract — turning the A7 schedule UI into a drop-in component.
 */
data class ScheduleUiState(
    val enabled: Boolean = false,
    val daysMask: Int = TimeSchedule.DAYS_ALL,
    val startHour: Int = 0,
    val endHour: Int = 0,
) {
    /**
     * Build a persistable [TimeSchedule]. When [enabled] is `false` or no
     * days are selected, returns the zero-mask sentinel (rule is always
     * active).
     */
    fun toSchedule(): TimeSchedule =
        if (!enabled || daysMask == 0) TimeSchedule()
        else TimeSchedule(daysMask, startHour, endHour)

    /** Validation gate surfaced on the Add button. */
    val needsDaySelection: Boolean get() = enabled && daysMask == 0
}

/**
 * Reusable schedule picker for the rule-add dialogs. Renders a Switch +
 * day chips + two HourPicker dropdowns. Emits state updates through
 * [onChange]; dialogs hold the [ScheduleUiState] themselves so they
 * survive recomposition together with pattern/description fields.
 */
@Composable
fun ScheduleSection(
    state: ScheduleUiState,
    onChange: (ScheduleUiState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.hash_wildcard_dialog_schedule_toggle),
            style = MaterialTheme.typography.bodyMedium,
            color = CatText,
        )
        Switch(
            checked = state.enabled,
            onCheckedChange = { onChange(state.copy(enabled = it)) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = CatBlue,
                checkedThumbColor = Black,
            ),
        )
    }
    if (state.enabled) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.hash_wildcard_dialog_schedule_days_label),
            style = MaterialTheme.typography.labelMedium,
            color = CatSubtext,
        )
        DayOfWeekChips(state.daysMask) { onChange(state.copy(daysMask = it)) }
        if (state.needsDaySelection) {
            Text(
                stringResource(R.string.hash_wildcard_dialog_schedule_needs_day),
                style = MaterialTheme.typography.bodySmall,
                color = CatRed,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.hash_wildcard_dialog_schedule_hours_label),
            style = MaterialTheme.typography.labelMedium,
            color = CatSubtext,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.hash_wildcard_dialog_schedule_start),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
                HourPicker(state.startHour) { onChange(state.copy(startHour = it)) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.hash_wildcard_dialog_schedule_end),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
                HourPicker(state.endHour) { onChange(state.copy(endHour = it)) }
            }
        }
        if (state.startHour == state.endHour) {
            Text(
                stringResource(R.string.hash_wildcard_dialog_schedule_all_day),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
        }
    }
}

/**
 * Day-of-week chip row. Horizontal scroll fallback for narrow widths /
 * verbose locales so we don't depend on the experimental FlowRow.
 */
@Composable
private fun DayOfWeekChips(daysMask: Int, onChange: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dayBit in 0..6) {
            val selected = (daysMask shr dayBit) and 1 == 1
            FilterChip(
                selected = selected,
                onClick = { onChange(daysMask xor (1 shl dayBit)) },
                label = { Text(TimeSchedule.DAY_LABELS[dayBit]) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CatBlue.copy(alpha = 0.25f),
                    selectedLabelColor = CatText,
                ),
            )
        }
    }
}

/**
 * "Active Mon–Fri · 09:00–17:00" pill used on rule-row cards. Renders
 * only when the schedule is actually gating — callers can call this
 * unconditionally and let the pill decide.
 */
@Composable
fun SchedulePill(schedule: TimeSchedule) {
    if (!schedule.isGating) return
    StatusPill(
        text = stringResource(R.string.hash_wildcard_item_schedule, schedule.describe()),
        color = CatBlue,
        horizontalPadding = 8.dp,
        verticalPadding = 4.dp,
        textStyle = MaterialTheme.typography.labelSmall,
    )
}
