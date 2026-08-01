package com.sysadmindoc.callshield.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsBodyRedactorTest {
    @Test
    fun `redactForPreview returns null for blank body`() {
        assertNull(SmsBodyRedactor.redactForPreview("  "))
    }

    @Test
    fun `redactForPreview hides message text codes and URL tokens`() {
        val rawBody = "Alice, use code 445566 at https://fraud.example/login?token=abc123"
        val preview = SmsBodyRedactor.redactForPreview(rawBody).orEmpty()

        assertTrue(preview.contains("SMS body redacted"))
        assertTrue(preview.contains("fraud.example"))
        assertTrue(preview.contains("code-like tokens hidden"))
        assertFalse(preview.contains("Alice"))
        assertFalse(preview.contains("445566"))
        assertFalse(preview.contains("token=abc123"))
    }

    @Test
    fun `redactForPreview summarizes email and phone-like tokens`() {
        val rawBody = "Contact bob@example.com or +1 (555) 123-4567 now"
        val preview = SmsBodyRedactor.redactForPreview(rawBody).orEmpty()

        assertTrue(preview.contains("email-like text hidden"))
        assertTrue(preview.contains("numbers hidden"))
        assertFalse(preview.contains("bob@example.com"))
        assertFalse(preview.contains("555"))
    }

    @Test
    fun `redactForPreview bounds all pattern scans while retaining the original length`() {
        val prefix = "a".repeat(SmsContentAnalyzer.MAX_ANALYSIS_LENGTH)
        val rawBody = "$prefix bob@example.com 5551234567"

        val preview = SmsBodyRedactor.redactForPreview(rawBody).orEmpty()

        assertTrue(preview.contains("${rawBody.length} chars"))
        assertFalse(preview.contains("email-like text hidden"))
        assertFalse(preview.contains("numbers hidden"))
    }
}
