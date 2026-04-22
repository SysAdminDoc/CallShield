package com.sysadmindoc.callshield.data.model

import com.sysadmindoc.callshield.data.TimeSchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class RuleValidationTest {

    @Test
    fun `blank keyword does not match any sms body`() {
        assertFalse(SmsKeywordRule(keyword = "   ").matches("free gift card"))
    }

    @Test
    fun `blank wildcard does not match any number`() {
        assertFalse(WildcardRule(pattern = "   ").matches("+12125551234"))
    }

    @Test
    fun `trimmed keyword still matches normally`() {
        assertTrue(SmsKeywordRule(keyword = "  urgent  ").matches("This is urgent"))
    }

    // ── WildcardRule multi-normalization ──────────────────────────────

    @Test
    fun `glob pattern with plus-1 prefix matches 10-digit number`() {
        val rule = WildcardRule(pattern = "+1212*")
        // SMS senders often arrive without the +1 prefix
        assertTrue(rule.matches("2125551234"))
    }

    @Test
    fun `glob pattern with plus-1 prefix matches full E164 number`() {
        val rule = WildcardRule(pattern = "+1212*")
        assertTrue(rule.matches("+12125551234"))
    }

    @Test
    fun `glob pattern with plus-1 prefix matches 11-digit number`() {
        val rule = WildcardRule(pattern = "+1212*")
        assertTrue(rule.matches("12125551234"))
    }

    @Test
    fun `glob pattern without prefix matches raw 10 digits`() {
        val rule = WildcardRule(pattern = "212*")
        assertTrue(rule.matches("2125551234"))
    }

    @Test
    fun `glob pattern does not match wrong area code`() {
        val rule = WildcardRule(pattern = "+1212*")
        assertFalse(rule.matches("+13105551234"))
        assertFalse(rule.matches("3105551234"))
    }

    @Test
    fun `numberVariants produces expected forms for 10-digit input`() {
        val variants = WildcardRule.numberVariants("2125551234")
        assertTrue("+12125551234" in variants)
        assertTrue("12125551234" in variants)
        assertTrue("2125551234" in variants)
    }

    @Test
    fun `numberVariants produces expected forms for plus-1 input`() {
        val variants = WildcardRule.numberVariants("+12125551234")
        assertTrue("+12125551234" in variants)
        assertTrue("12125551234" in variants)
        assertTrue("2125551234" in variants)
    }

    // ── A7: schedule-aware match ──────────────────────────────────────

    /** Wednesday 2026-04-22 12:00. */
    private fun wednesdayNoon(): Calendar = GregorianCalendar(2026, Calendar.APRIL, 22, 12, 0)
    /** Saturday 2026-04-18 12:00. */
    private fun saturdayNoon(): Calendar = GregorianCalendar(2026, Calendar.APRIL, 18, 12, 0)

    @Test
    fun `WildcardRule without schedule is always active via matchesNow`() {
        val rule = WildcardRule(pattern = "+1212*")
        assertTrue(rule.matchesNow("2125551234", wednesdayNoon()))
        assertTrue(rule.matchesNow("2125551234", saturdayNoon()))
    }

    @Test
    fun `WildcardRule with weekday schedule skips weekend matches`() {
        val sched = TimeSchedule.weekdaysAllDay()
        val rule = WildcardRule(
            pattern = "+1212*",
            scheduleDays = sched.daysMask,
            scheduleStartHour = sched.startHour,
            scheduleEndHour = sched.endHour,
        )
        assertTrue(rule.matchesNow("2125551234", wednesdayNoon()))
        assertFalse(rule.matchesNow("2125551234", saturdayNoon()))
    }

    @Test
    fun `SmsKeywordRule respects schedule via matchesNow`() {
        val rule = SmsKeywordRule(
            keyword = "urgent",
            scheduleDays = TimeSchedule.DAYS_WEEKDAYS,
            scheduleStartHour = 9,
            scheduleEndHour = 17,
        )
        // Inside window
        assertTrue(rule.matchesNow("This is urgent", wednesdayNoon()))
        // Same match, outside window
        val wednesdayEvening = GregorianCalendar(2026, Calendar.APRIL, 22, 20, 0)
        assertFalse(rule.matchesNow("This is urgent", wednesdayEvening))
    }
}
