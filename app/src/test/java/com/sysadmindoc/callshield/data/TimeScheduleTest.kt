package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class TimeScheduleTest {

    /** 2026-04-22 (Wednesday) at the given hour, local timezone. */
    private fun wednesdayAt(hour: Int): Calendar =
        GregorianCalendar(2026, Calendar.APRIL, 22, hour, 0)

    /** 2026-04-18 (Saturday). */
    private fun saturdayAt(hour: Int): Calendar =
        GregorianCalendar(2026, Calendar.APRIL, 18, hour, 0)

    /** 2026-04-19 (Sunday). */
    private fun sundayAt(hour: Int): Calendar =
        GregorianCalendar(2026, Calendar.APRIL, 19, hour, 0)

    // ── isGating sentinel ───────────────────────────────────────────

    @Test fun `default schedule is not gating`() {
        assertFalse(TimeSchedule().isGating)
    }

    @Test fun `schedule with only hour range but zero days is not gating`() {
        // Zero daysMask always means "no gating" even if hours are set.
        assertFalse(TimeSchedule(0, 9, 17).isGating)
    }

    @Test fun `schedule with any day bit set is gating`() {
        assertTrue(TimeSchedule(0b0000001, 0, 0).isGating)
        assertTrue(TimeSchedule(TimeSchedule.DAYS_ALL, 0, 0).isGating)
    }

    // ── isActiveAt: non-gating ──────────────────────────────────────

    @Test fun `non-gating schedule is always active`() {
        val sched = TimeSchedule()
        assertTrue(sched.isActiveAt(wednesdayAt(0)))
        assertTrue(sched.isActiveAt(wednesdayAt(12)))
        assertTrue(sched.isActiveAt(wednesdayAt(23)))
    }

    // ── isActiveAt: day-of-week only (hour endpoints equal) ─────────

    @Test fun `weekdays-only rule is active on Wednesday`() {
        assertTrue(TimeSchedule.weekdaysAllDay().isActiveAt(wednesdayAt(10)))
    }

    @Test fun `weekdays-only rule is inactive on Saturday`() {
        assertFalse(TimeSchedule.weekdaysAllDay().isActiveAt(saturdayAt(10)))
    }

    @Test fun `weekdays-only rule is inactive on Sunday`() {
        assertFalse(TimeSchedule.weekdaysAllDay().isActiveAt(sundayAt(10)))
    }

    @Test fun `weekend-only rule is active on Saturday and Sunday`() {
        val sched = TimeSchedule(TimeSchedule.DAYS_WEEKEND, 0, 0)
        assertTrue(sched.isActiveAt(saturdayAt(10)))
        assertTrue(sched.isActiveAt(sundayAt(10)))
    }

    @Test fun `weekend-only rule is inactive on Wednesday`() {
        val sched = TimeSchedule(TimeSchedule.DAYS_WEEKEND, 0, 0)
        assertFalse(sched.isActiveAt(wednesdayAt(10)))
    }

    // ── isActiveAt: hour range (non-wrapping) ───────────────────────

    @Test fun `business hours rule is active during business hours`() {
        val sched = TimeSchedule.everyDay(9, 17)
        assertTrue(sched.isActiveAt(wednesdayAt(9)))
        assertTrue(sched.isActiveAt(wednesdayAt(12)))
        assertTrue(sched.isActiveAt(wednesdayAt(16)))
    }

    @Test fun `business hours rule is inactive outside business hours`() {
        val sched = TimeSchedule.everyDay(9, 17)
        assertFalse(sched.isActiveAt(wednesdayAt(8)))
        assertFalse(sched.isActiveAt(wednesdayAt(17)))  // end is exclusive
        assertFalse(sched.isActiveAt(wednesdayAt(20)))
    }

    // ── isActiveAt: hour range (wrapping over midnight) ─────────────

    @Test fun `quiet hours 22 to 6 matches late night`() {
        val sched = TimeSchedule.everyDay(22, 6)
        assertTrue(sched.isActiveAt(wednesdayAt(22)))
        assertTrue(sched.isActiveAt(wednesdayAt(23)))
        assertTrue(sched.isActiveAt(wednesdayAt(0)))
        assertTrue(sched.isActiveAt(wednesdayAt(5)))
    }

    @Test fun `quiet hours 22 to 6 does not match mid-morning`() {
        val sched = TimeSchedule.everyDay(22, 6)
        assertFalse(sched.isActiveAt(wednesdayAt(6)))
        assertFalse(sched.isActiveAt(wednesdayAt(10)))
        assertFalse(sched.isActiveAt(wednesdayAt(21)))
    }

    // ── isActiveAt: day + hour intersection ─────────────────────────

    @Test fun `weekday business hours active on Wednesday at noon`() {
        val sched = TimeSchedule(TimeSchedule.DAYS_WEEKDAYS, 9, 17)
        assertTrue(sched.isActiveAt(wednesdayAt(12)))
    }

    @Test fun `weekday business hours inactive on Saturday at noon`() {
        val sched = TimeSchedule(TimeSchedule.DAYS_WEEKDAYS, 9, 17)
        assertFalse(sched.isActiveAt(saturdayAt(12)))
    }

    @Test fun `weekday business hours inactive on Wednesday at 8pm`() {
        val sched = TimeSchedule(TimeSchedule.DAYS_WEEKDAYS, 9, 17)
        assertFalse(sched.isActiveAt(wednesdayAt(20)))
    }

    // ── describe() ──────────────────────────────────────────────────

    @Test fun `describe for non-gating schedule is empty`() {
        assertEquals("", TimeSchedule().describe())
    }

    @Test fun `describe every day all hours is just Every day`() {
        assertEquals("Every day", TimeSchedule(TimeSchedule.DAYS_ALL, 0, 0).describe())
    }

    @Test fun `describe every day 9 to 17`() {
        assertEquals("Every day · 09:00–17:00", TimeSchedule.everyDay(9, 17).describe())
    }

    @Test fun `describe weekdays 9 to 17`() {
        assertEquals(
            "Mon–Fri · 09:00–17:00",
            TimeSchedule(TimeSchedule.DAYS_WEEKDAYS, 9, 17).describe()
        )
    }

    @Test fun `describe weekends all hours`() {
        assertEquals("Weekends", TimeSchedule(TimeSchedule.DAYS_WEEKEND, 0, 0).describe())
    }

    @Test fun `describe arbitrary day selection lists the days`() {
        // Monday + Thursday only
        val mask = (1 shl 1) or (1 shl 4)
        assertEquals("Mon, Thu", TimeSchedule(mask, 0, 0).describe())
    }
}
