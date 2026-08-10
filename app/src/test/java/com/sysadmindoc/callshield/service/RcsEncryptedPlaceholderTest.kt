package com.sysadmindoc.callshield.service

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sysadmindoc.callshield.data.SpamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for locale-tolerant RCS encrypted-placeholder detection.
 * The old check matched English literals only, so on non-English devices the
 * localized placeholder was fed to the SMS content rules as real content.
 * Detection is now keyword-anchored and length-bounded so it covers Latin
 * locales without swallowing real short spam.
 */
class RcsEncryptedPlaceholderTest {
    @Test
    fun `URL warning access stays enabled when SMS blocking is disabled`() {
        val prefs =
            mutablePreferencesOf(
                SpamRepository.KEY_RCS_FILTER to true,
                SpamRepository.KEY_BLOCK_SMS to false,
            )

        assertTrue(RcsNotificationListener.isNotificationScreeningEnabled(prefs))
        assertFalse(RcsNotificationListener.isSpamBlockingEnabled(prefs))
    }

    @Test
    fun `notification screening toggle disables content access`() {
        val prefs = mutablePreferencesOf(SpamRepository.KEY_RCS_FILTER to false)

        assertFalse(RcsNotificationListener.isNotificationScreeningEnabled(prefs))
    }

    @Test
    fun `notification content verdict flags strong spam locally`() {
        val verdict =
            RcsNotificationListener.contentVerdict(
                body = "Congratulations, you have won. Claim your prize now at bit.ly/example",
                enabled = true,
                aggressive = false,
            )

        assertTrue(verdict.isSpam)
        assertTrue(verdict.confidence >= 50)
    }

    @Test
    fun `notification content verdict honors disabled analysis`() {
        val verdict =
            RcsNotificationListener.contentVerdict(
                body = "Claim your prize now at bit.ly/example",
                enabled = false,
                aggressive = true,
            )

        assertFalse(verdict.isSpam)
        assertEquals(0, verdict.confidence)
    }

    @Test
    fun `aggressive notification screening lowers the local threshold`() {
        val normal = RcsNotificationListener.contentVerdict("unsubscribe", enabled = true, aggressive = false)
        val aggressive = RcsNotificationListener.contentVerdict("unsubscribe", enabled = true, aggressive = true)

        assertFalse(normal.isSpam)
        assertTrue(aggressive.isSpam)
    }

    @Test
    fun `english placeholders are detected`() {
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("Encrypted message"))
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("This message is encrypted"))
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("End-to-end encrypted"))
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("🔒 Encrypted"))
    }

    @Test
    fun `localized placeholders are detected`() {
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("Mensaje cifrado")) // es
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("Message chiffré")) // fr
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("Verschlüsselte Nachricht")) // de
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("Messaggio crittografato")) // it
    }

    @Test
    fun `blank body is treated as a placeholder`() {
        assertTrue(RcsNotificationListener.isEncryptedPlaceholder("   "))
    }

    @Test
    fun `real short spam is not misclassified as a placeholder`() {
        assertFalse(RcsNotificationListener.isEncryptedPlaceholder("You won! bit.ly/x"))
        assertFalse(RcsNotificationListener.isEncryptedPlaceholder("Your code is 483920"))
        assertFalse(RcsNotificationListener.isEncryptedPlaceholder("Call me back please"))
    }

    @Test
    fun `long message containing the word is not a placeholder`() {
        val long =
            "Our new banking app uses encrypted connections to protect every " +
                "transaction you make, tap here to learn more about it today"
        assertFalse(RcsNotificationListener.isEncryptedPlaceholder(long))
    }

    @Test
    fun `redacted body stays sender-only while real URL content remains available`() {
        assertNull(RcsNotificationListener.bodyForAnalysis("Encrypted message"))
        val body = "Review your account at https://example.test/login"
        assertEquals(body, RcsNotificationListener.bodyForAnalysis(body))
        assertFalse(RcsNotificationListener.contentVerdict("", enabled = true, aggressive = true).isSpam)
    }
}
