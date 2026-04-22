package com.sysadmindoc.callshield.data

import java.util.Calendar

/**
 * Day-of-week + hour-window gate for detection rules (A7).
 *
 * Stored as three plain columns on a rule entity — no separate table,
 * no nullable embed — because Room's @Embedded doesn't play well with
 * the "rule may or may not have a schedule" case without sentinel values
 * anyway. The sentinel we use is `daysMask == 0` → the schedule does
 * nothing, the rule is always active. See [isGating].
 *
 * Inspired by SpamBlocker's `RegexRule.schedule` (github.com/aj3423/SpamBlocker).
 *
 * ## Semantics
 *
 *  - **Days**: 7-bit mask. Bit 0 = Sunday, …, bit 6 = Saturday (matches
 *    Calendar's `Calendar.SUNDAY..Calendar.SATURDAY` as `1..7`, shifted to
 *    zero-based bit positions). `0` means "schedule does not gate", not
 *    "never active" — a rule with no schedule is always active.
 *  - **Hours**: 0..23, `startHour` inclusive, `endHour` exclusive.
 *    `startHour == endHour` means "no hour filter on the chosen days".
 *    `startHour > endHour` wraps over midnight (e.g. `22..6` = 22:00
 *    through 05:59 next day), matching [com.sysadmindoc.callshield.data.checker.TimeBlockChecker].
 *
 * The "all off = no gating" convention matches how we persist the legacy
 * quiet-hours feature, which avoids the "null schedule vs zero-valued
 * schedule" footgun and means a rule inserted via direct SQL with no
 * explicit schedule columns behaves sensibly.
 */
data class TimeSchedule(
    /** Bit 0 = Sunday, …, bit 6 = Saturday. `0` means "always". */
    val daysMask: Int = 0,
    val startHour: Int = 0,
    val endHour: Int = 0,
) {
    /**
     * `true` iff this schedule actually restricts activity. Rules with a
     * zero daysMask are always active — [isActiveAt] returns `true`
     * regardless of input — so checkers can short-circuit.
     */
    val isGating: Boolean get() = daysMask != 0

    /**
     * `true` iff the rule is active at [calendar]'s day-of-week and hour.
     * Callers should pass `Calendar.getInstance()` at decision time so
     * the local timezone is respected.
     */
    fun isActiveAt(calendar: Calendar): Boolean {
        if (!isGating) return true

        // Calendar.SUNDAY == 1, …, Calendar.SATURDAY == 7. Convert to 0..6.
        val dayBit = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        if (dayBit !in 0..6) return true  // defensive — should never happen
        if ((daysMask shr dayBit) and 1 == 0) return false

        // Hours: equal endpoints disable hour filter on selected days.
        if (startHour == endHour) return true
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        val h = calendar.get(Calendar.HOUR_OF_DAY)
        return if (s < e) h in s until e
               else h >= s || h < e   // wraps midnight
    }

    /**
     * Short label for UI: "Mon–Fri 09–17" / "Weekends" / "Every day 22–06".
     * Callers typically only show this when [isGating] is true.
     */
    fun describe(): String {
        if (!isGating) return ""
        val days = describeDays()
        val hours = describeHours()
        return if (hours.isEmpty()) days else "$days · $hours"
    }

    private fun describeDays(): String {
        val set = (0..6).filter { (daysMask shr it) and 1 == 1 }
        return when {
            set.size == 7 -> "Every day"
            set == listOf(1, 2, 3, 4, 5) -> "Mon–Fri"
            set == listOf(0, 6) -> "Weekends"
            else -> set.joinToString(", ") { DAY_LABELS[it] }
        }
    }

    private fun describeHours(): String {
        if (startHour == endHour) return ""
        return "%02d:00–%02d:00".format(startHour, endHour)
    }

    companion object {
        /** 0 = Sunday, 6 = Saturday — matches the bit positions in [daysMask]. */
        val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        const val DAYS_NONE = 0
        const val DAYS_WEEKDAYS = 0b0111110   // Mon–Fri
        const val DAYS_WEEKEND  = 0b1000001   // Sun + Sat
        const val DAYS_ALL      = 0b1111111

        /** Convenience: build a [TimeSchedule] for "every day, given hours". */
        fun everyDay(startHour: Int, endHour: Int): TimeSchedule =
            TimeSchedule(DAYS_ALL, startHour, endHour)

        /** Convenience: build a [TimeSchedule] for "weekdays only, all hours". */
        fun weekdaysAllDay(): TimeSchedule = TimeSchedule(DAYS_WEEKDAYS, 0, 0)
    }
}
