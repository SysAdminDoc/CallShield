package com.sysadmindoc.callshield.ui

import java.util.Calendar

internal data class DashboardTimeWindows(
    val todayStart: Long,
    val weekStart: Long,
    val lastWeekStart: Long,
    val lastWeekEnd: Long,
)

internal fun buildDashboardTimeWindows(
    nowMillis: Long,
    firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek,
): DashboardTimeWindows {
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val weekStart = Calendar.getInstance().apply {
        timeInMillis = todayStart.timeInMillis
        this.firstDayOfWeek = firstDayOfWeek
        while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) {
            add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    val lastWeekStart = Calendar.getInstance().apply {
        timeInMillis = weekStart.timeInMillis
        add(Calendar.DAY_OF_YEAR, -7)
    }

    return DashboardTimeWindows(
        todayStart = todayStart.timeInMillis,
        weekStart = weekStart.timeInMillis,
        lastWeekStart = lastWeekStart.timeInMillis,
        lastWeekEnd = weekStart.timeInMillis
    )
}
