package com.sysadmindoc.callshield.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupCryptoTest {
    private val passphrase = "correct horse battery staple".toCharArray()
    private val plaintext = "{\"app\":\"CallShield\",\"private\":\"+15551234567\"}".toByteArray()

    @Test
    fun `encrypted envelope round trips without exposing plaintext`() {
        val envelope = PortableBackupCrypto.encrypt(plaintext, passphrase)

        assertFalse(envelope.toString(Charsets.UTF_8).contains("+15551234567"))
        val result = PortableBackupCrypto.decrypt(envelope, passphrase)

        assertTrue(result is PortableBackupCrypto.DecryptionResult.Success)
        assertArrayEquals(plaintext, (result as PortableBackupCrypto.DecryptionResult.Success).plaintext)
    }

    @Test
    fun `fresh salt and nonce produce different envelopes`() {
        val first = PortableBackupCrypto.encrypt(plaintext, passphrase)
        val second = PortableBackupCrypto.encrypt(plaintext, passphrase)

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `encrypted envelope requires a passphrase`() {
        val envelope = PortableBackupCrypto.encrypt(plaintext, passphrase)

        assertEquals(
            PortableBackupCrypto.DecryptionResult.PassphraseRequired,
            PortableBackupCrypto.decrypt(envelope, null),
        )
    }

    @Test
    fun `wrong passphrase fails authentication`() {
        val envelope = PortableBackupCrypto.encrypt(plaintext, passphrase)
        val result = PortableBackupCrypto.decrypt(envelope, "incorrect passphrase".toCharArray())

        assertEquals(
            PortableBackupCrypto.InvalidReason.AUTHENTICATION_FAILED,
            (result as PortableBackupCrypto.DecryptionResult.Invalid).reason,
        )
    }

    @Test
    fun `ciphertext tampering fails authentication`() {
        val envelope = PortableBackupCrypto.encrypt(plaintext, passphrase).copyOf()
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()

        val result = PortableBackupCrypto.decrypt(envelope, passphrase)

        assertEquals(
            PortableBackupCrypto.InvalidReason.AUTHENTICATION_FAILED,
            (result as PortableBackupCrypto.DecryptionResult.Invalid).reason,
        )
    }

    @Test
    fun `header tampering is rejected before key derivation`() {
        val envelope = PortableBackupCrypto.encrypt(plaintext, passphrase).copyOf()
        val iterationsOffset = "CALLSHIELD-BACKUP".length + 1
        envelope[iterationsOffset] = (envelope[iterationsOffset].toInt() xor 1).toByte()

        val result = PortableBackupCrypto.decrypt(envelope, passphrase)

        assertEquals(
            PortableBackupCrypto.InvalidReason.INVALID_FORMAT,
            (result as PortableBackupCrypto.DecryptionResult.Invalid).reason,
        )
    }

    @Test
    fun `plaintext legacy backup is not treated as an envelope`() {
        assertEquals(
            PortableBackupCrypto.DecryptionResult.NotEncrypted,
            PortableBackupCrypto.decrypt(plaintext, passphrase),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `new backup rejects short passphrase`() {
        PortableBackupCrypto.encrypt(plaintext, "too short".toCharArray())
    }
}
