package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PhoneFormatter — phone number display formatting.
 */
class PhoneFormatterTest {

    // ── format(): US 10-digit numbers ────────────────────────────────────

    @Test
    fun `format 10-digit US number`() {
        assertEquals("(212) 555-1234", PhoneFormatter.format("2125551234"))
    }

    @Test
    fun `format 10-digit US number with different area code`() {
        assertEquals("(310) 555-9876", PhoneFormatter.format("3105559876"))
    }

    // ── format(): US 11-digit with country code ──────────────────────────

    @Test
    fun `format 11-digit US number with leading 1`() {
        assertEquals("(212) 555-1234", PhoneFormatter.format("12125551234"))
    }

    @Test
    fun `format 11-digit strips country code and formats`() {
        assertEquals("(800) 555-0199", PhoneFormatter.format("18005550199"))
    }

    // ── format(): international numbers ──────────────────────────────────

    @Test
    fun `format international number with plus prefix`() {
        assertEquals("+442012345678", PhoneFormatter.format("+442012345678"))
    }

    @Test
    fun `format international number preserves plus and strips formatting`() {
        assertEquals("+4915123456789", PhoneFormatter.format("+49-151-2345-6789"))
    }

    @Test
    fun `format international number without plus passes through`() {
        // No + prefix and >11 digits, not US-parseable
        assertEquals("442012345678", PhoneFormatter.format("442012345678"))
    }

    // ── format(): short codes ────────────────────────────────────────────

    @Test
    fun `format 5-digit short code`() {
        assertEquals("55555", PhoneFormatter.format("55555"))
    }

    @Test
    fun `format 6-digit short code`() {
        assertEquals("123456", PhoneFormatter.format("123456"))
    }

    // ── format(): empty and edge cases ───────────────────────────────────

    @Test
    fun `format empty string returns empty`() {
        assertEquals("", PhoneFormatter.format(""))
    }

    @Test
    fun `format non-digit characters only`() {
        // No digits -> digits.length == 0, not 10 or 11, not 5-6, not international
        assertEquals("abc-def", PhoneFormatter.format("abc-def"))
    }

    @Test
    fun `format number with existing formatting parens and dashes`() {
        assertEquals("(212) 555-1234", PhoneFormatter.format("(212) 555-1234"))
    }

    @Test
    fun `format number with spaces`() {
        assertEquals("(212) 555-1234", PhoneFormatter.format("212 555 1234"))
    }

    @Test
    fun `format number with plus and 10 digits`() {
        // "+2125551234" — digits = "2125551234" (10), formatted as US
        assertEquals("(212) 555-1234", PhoneFormatter.format("+2125551234"))
    }

    @Test
    fun `format 4-digit number passes through`() {
        // Not 5-6 (short code), not 10/11 (US), not international
        assertEquals("1234", PhoneFormatter.format("1234"))
    }

    @Test
    fun `format 7-digit number passes through`() {
        assertEquals("5551234", PhoneFormatter.format("5551234"))
    }

    @Test
    fun `format 12-digit number without plus passes through`() {
        // 12 digits, no +, falls through to return original
        assertEquals("123456789012", PhoneFormatter.format("123456789012"))
    }

    // ── formatWithCountryCode(): US numbers ──────────────────────────────

    @Test
    fun `formatWithCountryCode for 10-digit US number`() {
        assertEquals("+1 (212) 555-1234", PhoneFormatter.formatWithCountryCode("2125551234"))
    }

    @Test
    fun `formatWithCountryCode for 11-digit US number`() {
        assertEquals("+1 (212) 555-1234", PhoneFormatter.formatWithCountryCode("12125551234"))
    }

    // ── formatWithCountryCode(): international ───────────────────────────

    @Test
    fun `formatWithCountryCode preserves plus for international`() {
        assertEquals("+442012345678", PhoneFormatter.formatWithCountryCode("+442012345678"))
    }

    @Test
    fun `formatWithCountryCode adds plus for non-US without plus`() {
        assertEquals("+442012345678", PhoneFormatter.formatWithCountryCode("442012345678"))
    }

    @Test
    fun `formatWithCountryCode with formatted US number`() {
        assertEquals("+1 (212) 555-1234", PhoneFormatter.formatWithCountryCode("(212) 555-1234"))
    }
}
