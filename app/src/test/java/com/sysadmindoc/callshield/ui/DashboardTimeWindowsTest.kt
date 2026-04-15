package com.sysadmindoc.callshield.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DashboardTimeWindowsTest {

    private val testTimeZone = TimeZone.getTimeZone("America/New_York")
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(testTimeZone)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `buildDashboardTimeWindows snaps today to local midnight`() {
        val now = calendarMillis(2026, Calendar.APRIL, 15, 18, 42)

        val windows = buildDashboardTimeWindows(now, firstDayOfWeek = Calendar.MONDAY)

        assertEquals(calendarMillis(2026, Calendar.APRIL, 15, 0, 0), windows.todayStart)
    }

    @Test
    fun `buildDashboardTimeWindows honors monday week start`() {
        val now = calendarMillis(2026, Calendar.APRIL, 15, 18, 42)

        val windows = buildDashboardTimeWindows(now, firstDayOfWeek = Calendar.MONDAY)

        assertEquals(calendarMillis(2026, Calendar.APRIL, 13, 0, 0), windows.weekStart)
        assertEquals(calendarMillis(2026, Calendar.APRIL, 6, 0, 0), windows.lastWeekStart)
        assertEquals(calendarMillis(2026, Calendar.APRIL, 13, 0, 0), windows.lastWeekEnd)
    }

    @Test
    fun `buildDashboardTimeWindows honors sunday week start`() {
        val now = calendarMillis(2026, Calendar.APRIL, 15, 18, 42)

        val windows = buildDashboardTimeWindows(now, firstDayOfWeek = Calendar.SUNDAY)

        assertEquals(calendarMillis(2026, Calendar.APRIL, 12, 0, 0), windows.weekStart)
        assertEquals(calendarMillis(2026, Calendar.APRIL, 5, 0, 0), windows.lastWeekStart)
        assertEquals(calendarMillis(2026, Calendar.APRIL, 12, 0, 0), windows.lastWeekEnd)
    }

    private fun calendarMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(testTimeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
