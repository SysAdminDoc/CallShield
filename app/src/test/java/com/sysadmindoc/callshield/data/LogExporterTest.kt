package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for LogExporter — CSV export logic.
 * Tests the csvEscape private method via reflection.
 */
class LogExporterTest {

    private val csvEscape: Method = LogExporter::class.java.getDeclaredMethod("csvEscape", String::class.java).apply {
        isAccessible = true
    }

    private fun escape(value: String): String =
        csvEscape.invoke(LogExporter, value) as String

    // ── csvEscape: plain text ────────────────────────────────────────────

    @Test
    fun `csvEscape wraps plain text in quotes`() {
        assertEquals("\"hello\"", escape("hello"))
    }

    @Test
    fun `csvEscape wraps empty string in quotes`() {
        assertEquals("\"\"", escape(""))
    }

    @Test
    fun `csvEscape wraps single word in quotes`() {
        assertEquals("\"test\"", escape("test"))
    }

    // ── csvEscape: text with commas ──────────────────────────────────────

    @Test
    fun `csvEscape handles text with comma`() {
        assertEquals("\"hello, world\"", escape("hello, world"))
    }

    @Test
    fun `csvEscape handles multiple commas`() {
        assertEquals("\"a,b,c,d\"", escape("a,b,c,d"))
    }

    // ── csvEscape: text with double quotes ───────────────────────────────

    @Test
    fun `csvEscape doubles internal quotes`() {
        assertEquals("\"say \"\"hello\"\"\"", escape("say \"hello\""))
    }

    @Test
    fun `csvEscape handles single double quote`() {
        assertEquals("\"\"\"\"", escape("\""))
    }

    @Test
    fun `csvEscape handles multiple double quotes`() {
        assertEquals("\"\"\"a\"\" and \"\"b\"\"\"", escape("\"a\" and \"b\""))
    }

    // ── csvEscape: text with newlines ────────────────────────────────────

    @Test
    fun `csvEscape replaces newline with space`() {
        assertEquals("\"line1 line2\"", escape("line1\nline2"))
    }

    @Test
    fun `csvEscape removes carriage return`() {
        assertEquals("\"line1 line2\"", escape("line1\r\nline2"))
    }

    @Test
    fun `csvEscape handles multiple newlines`() {
        assertEquals("\"a b c\"", escape("a\nb\nc"))
    }

    @Test
    fun `csvEscape handles carriage return only`() {
        assertEquals("\"ab\"", escape("a\rb"))
    }

    // ── csvEscape: combined special characters ───────────────────────────

    @Test
    fun `csvEscape handles quotes commas and newlines together`() {
        assertEquals("\"He said \"\"hi,\"\" then left\"", escape("He said \"hi,\" then\nleft"))
    }

    @Test
    fun `csvEscape handles phone number format`() {
        assertEquals("\"(212) 555-1234\"", escape("(212) 555-1234"))
    }

    @Test
    fun `csvEscape handles date format`() {
        assertEquals("\"2024-01-15 14:30:00\"", escape("2024-01-15 14:30:00"))
    }

    @Test
    fun `csvEscape handles SMS body with spam content`() {
        val body = "You've won \$1000! Click here: https://spam.xyz"
        assertEquals("\"$body\"", escape(body))
    }

    @Test
    fun `csvEscape handles long text with special chars`() {
        val input = "This is a \"test\" message,\nwith multiple lines\r\nand various, special characters"
        val expected = "\"This is a \"\"test\"\" message, with multiple lines and various, special characters\""
        assertEquals(expected, escape(input))
    }
}
