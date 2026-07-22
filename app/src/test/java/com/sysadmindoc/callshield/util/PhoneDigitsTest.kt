package com.sysadmindoc.callshield.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDigitsTest {
    @Test
    fun `lookup input rejects unicode-only phone digits`() {
        val unicodeDigits = "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660"

        assertEquals("", sanitizePhoneNumberInput(unicodeDigits))
        assertEquals("", normalizePhoneNumberInput(unicodeDigits))
        assertFalse(hasMinAsciiDigits(normalizePhoneNumberInput(unicodeDigits)))
    }

    @Test
    fun `manual block input keeps ascii digits and drops homoglyph digits`() {
        val mixed = "+1 (\u0662\u0661\u0662) 555-\uFF10\uFF11\uFF10\uFF11"

        assertEquals("+1 () 555-", sanitizePhoneNumberInput(mixed))
        assertEquals("+1555", normalizePhoneNumberInput(mixed))
        assertFalse(hasMinAsciiDigits(normalizePhoneNumberInput(mixed)))
    }

    @Test
    fun `whitelist input accepts normal formatted ascii numbers`() {
        val formatted = "+1 (212) 555-0101"

        assertEquals(formatted, sanitizePhoneNumberInput(formatted))
        assertEquals("+12125550101", normalizePhoneNumberInput(formatted))
        assertTrue(hasMinAsciiDigits(normalizePhoneNumberInput(formatted)))
    }

    @Test
    fun `log and recent-call search digits are ascii only`() {
        val spoofed = "+\u0661\u0662 1 (212) \uFF15555-0101"

        assertEquals("12125550101", filterAsciiDigits(spoofed))
    }

    @Test
    fun `normalization bounds untrusted source length`() {
        val oversized = "+" + "1".repeat(10_000)

        assertEquals("+" + "1".repeat(24), normalizePhoneNumberInput(oversized))
        assertEquals("1".repeat(24), sanitizePhoneNumberInput("1".repeat(10_000)))
    }

    @Test
    fun `normalization keeps a plus after leading whitespace and format controls`() {
        val formatted = " \u200E+1 (212) 555-0101"

        assertEquals("+12125550101", normalizePhoneNumberInput(formatted))
    }
}
