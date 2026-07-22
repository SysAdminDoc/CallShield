package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the canonical phone-number normalizer. v1.7.2 hardens this
 * against Unicode-digit homoglyph spoofing: only ASCII '0'..'9' are kept,
 * so a caller-ID using Arabic-Indic or fullwidth digits is correctly
 * collapsed to the same canonical form as the ASCII original (or to an
 * empty string when no ASCII digits are present).
 */
class NormalizePhoneNumberTest {
    @Test fun `plain US 10-digit passes through`() {
        assertEquals("2125551234", normalizePhoneNumber("2125551234"))
    }

    @Test fun `formatting characters are stripped`() {
        assertEquals("12125551234", normalizePhoneNumber("1 (212) 555-1234"))
    }

    @Test fun `leading plus is preserved`() {
        assertEquals("+12125551234", normalizePhoneNumber("+1 212-555-1234"))
    }

    @Test fun `embedded plus is dropped (not a country-code prefix)`() {
        assertEquals("12125551234", normalizePhoneNumber("1 +2125551234"))
    }

    @Test fun `whitespace-only returns empty`() {
        assertEquals("", normalizePhoneNumber("   "))
    }

    // ── Unicode digit hardening (v1.7.2) ────────────────────────────

    @Test fun `Arabic-Indic digits are NOT treated as digits`() {
        // U+0660..U+0669 — Char.isDigit() returns true, but these are
        // not the ASCII digits the telecom stack actually dials.
        val arabicIndic = "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660"
        assertEquals("", normalizePhoneNumber(arabicIndic))
    }

    @Test fun `Fullwidth digits are NOT treated as digits`() {
        // U+FF10..U+FF19 — visually identical to ASCII digits on many
        // fonts; a perfect homoglyph spoof.
        val fullwidth = "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10"
        assertEquals("", normalizePhoneNumber(fullwidth))
    }

    @Test fun `mixed ASCII and Unicode digits only keep ASCII`() {
        // The ASCII subset normalises; the Unicode digits are dropped.
        // Prevents an attacker from constructing a number that visually
        // matches a blocklisted entry but bypasses exact-match lookup.
        assertEquals("212", normalizePhoneNumber("2\uFF111\uFF122"))
    }

    @Test fun `RTL marks and zero-width chars are ignored`() {
        // U+200E LRM, U+200F RLM, U+200B ZWSP — sometimes injected into
        // notification text to break naive parsing. We strip them.
        val payload = "\u200E+\u200F1 212\u200B-555\u200E-1234"
        assertEquals("+12125551234", normalizePhoneNumber(payload))
    }

    @Test fun `numbers beyond E164 length are rejected instead of truncated`() {
        assertEquals("123456789012345", normalizePhoneNumber("123456789012345"))
        assertEquals("", normalizePhoneNumber("1234567890123456"))
    }

    @Test fun `oversized raw caller IDs are rejected with bounded work`() {
        assertEquals("", normalizePhoneNumber("x".repeat(10_000) + "2125551234"))
    }
}
