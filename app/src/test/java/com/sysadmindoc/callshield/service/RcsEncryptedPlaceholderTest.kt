package com.sysadmindoc.callshield.service

import org.junit.Assert.assertFalse
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
}
