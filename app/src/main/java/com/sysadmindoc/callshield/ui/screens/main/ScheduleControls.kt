package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.ui.screens.settings.HourPicker
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
        if (!enabled || daysMask == 0) {
            TimeSchedule()
        } else {
            TimeSchedule(daysMask, startHour, endHour)
        }

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
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.enabled,
                    role = Role.Switch,
                    onValueChange = { onChange(state.copy(enabled = it)) },
                ),
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
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CatBlue,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
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
private fun DayOfWeekChips(
    daysMask: Int,
    onChange: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier =
            Modifier
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
                label = { Text(localizedDayLabel(dayBit)) },
                shape = RoundedCornerShape(8.dp),
                colors =
                    FilterChipDefaults.filterChipColors(
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
        text = stringResource(R.string.hash_wildcard_item_schedule, localizedScheduleDescription(schedule)),
        color = CatBlue,
        horizontalPadding = 8.dp,
        verticalPadding = 4.dp,
        textStyle = MaterialTheme.typography.labelSmall,
    )
}

/**
 * Locale-aware short weekday label. Uses the platform's localized short weekday
 * names (Sun/Mon/… in en, dim./lun./… in fr, etc.), falling back to the ASCII
 * [TimeSchedule.DAY_LABELS] only if the platform returns a blank entry.
 * `dayBit` is 0 = Sunday … 6 = Saturday; DateFormatSymbols indexes 1 = Sunday.
 */
@Composable
private fun localizedDayLabel(dayBit: Int): String {
    val shortWeekdays =
        remember {
            java.text.DateFormatSymbols
                .getInstance()
                .shortWeekdays
        }
    return shortWeekdays.getOrNull(dayBit + 1)?.takeIf { it.isNotBlank() }
        ?: TimeSchedule.DAY_LABELS[dayBit]
}

/**
 * Localized equivalent of [TimeSchedule.describe] for the UI. The non-composable
 * `describe()` stays as the canonical/logging form; this renders translated
 * "Every day / Mon–Fri / Weekends" and locale-aware weekday names for display.
 */
@Composable
private fun localizedScheduleDescription(schedule: TimeSchedule): String {
    if (!schedule.isGating) return ""
    val set = (0..6).filter { (schedule.daysMask shr it) and 1 == 1 }
    val shortWeekdays =
        remember {
            java.text.DateFormatSymbols
                .getInstance()
                .shortWeekdays
        }
    val days =
        when {
            set.size == 7 -> {
                stringResource(R.string.schedule_every_day)
            }

            set == listOf(1, 2, 3, 4, 5) -> {
                stringResource(R.string.schedule_weekdays)
            }

            set == listOf(0, 6) -> {
                stringResource(R.string.schedule_weekends)
            }

            else -> {
                set.joinToString(", ") { dayBit ->
                    shortWeekdays.getOrNull(dayBit + 1)?.takeIf { it.isNotBlank() }
                        ?: TimeSchedule.DAY_LABELS[dayBit]
                }
            }
        }
    val hours =
        if (schedule.startHour == schedule.endHour) {
            ""
        } else {
            "%02d:00–%02d:00".format(schedule.startHour, schedule.endHour)
        }
    return if (hours.isEmpty()) days else stringResource(R.string.schedule_days_hours, days, hours)
}
