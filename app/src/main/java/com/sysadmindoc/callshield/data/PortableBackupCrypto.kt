package com.sysadmindoc.callshield.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned authenticated encryption for user-exported portable backups.
 *
 * Envelope v1 uses PBKDF2-HMAC-SHA256 with a fresh 128-bit salt and AES-256-GCM
 * with a fresh 96-bit nonce. The complete header is authenticated as AAD, so
 * algorithm parameters cannot be changed without invalidating the GCM tag.
 */
internal object PortableBackupCrypto {
    const val MIN_PASSPHRASE_LENGTH = 12
    const val MAX_PASSPHRASE_LENGTH = 128
    const val KDF_ITERATIONS = 600_000

    private const val FORMAT_VERSION: Byte = 1
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private val magic = "CALLSHIELD-BACKUP".toByteArray(StandardCharsets.US_ASCII)
    private val headerBytes = magic.size + Byte.SIZE_BYTES + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES + Int.SIZE_BYTES

    internal val maxEnvelopeBytes: Long = MAX_IMPORT_FILE_BYTES + headerBytes + GCM_TAG_BYTES

    sealed interface DecryptionResult {
        data object NotEncrypted : DecryptionResult

        data object PassphraseRequired : DecryptionResult

        data class Success(
            val plaintext: ByteArray,
        ) : DecryptionResult

        data class Invalid(
            val reason: InvalidReason,
        ) : DecryptionResult
    }

    enum class InvalidReason {
        INVALID_FORMAT,
        UNSUPPORTED_VERSION,
        AUTHENTICATION_FAILED,
        TOO_LARGE,
    }

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        secureRandom: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase is too short" }
        require(passphrase.size <= MAX_PASSPHRASE_LENGTH) { "Passphrase is too long" }
        require(plaintext.size.toLong() <= MAX_IMPORT_FILE_BYTES) { "Backup is too large" }

        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val ciphertextBytes = plaintext.size + GCM_TAG_BYTES
        val header =
            ByteBuffer
                .allocate(headerBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .put(magic)
                .put(FORMAT_VERSION)
                .putInt(KDF_ITERATIONS)
                .put(salt)
                .put(nonce)
                .putInt(ciphertextBytes)
                .array()

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintext)
        return header + ciphertext
    }

    @Suppress("ReturnCount")
    fun decrypt(
        envelope: ByteArray,
        passphrase: CharArray?,
    ): DecryptionResult {
        if (!envelope.startsWith(magic)) return DecryptionResult.NotEncrypted
        if (envelope.size.toLong() > maxEnvelopeBytes) {
            return DecryptionResult.Invalid(InvalidReason.TOO_LARGE)
        }
        if (envelope.size < headerBytes + GCM_TAG_BYTES) {
            return DecryptionResult.Invalid(InvalidReason.INVALID_FORMAT)
        }

        val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        buffer.position(magic.size)
        val version = buffer.get()
        if (version != FORMAT_VERSION) {
            return DecryptionResult.Invalid(InvalidReason.UNSUPPORTED_VERSION)
        }
        val iterations = buffer.int
        if (iterations != KDF_ITERATIONS) {
            return DecryptionResult.Invalid(InvalidReason.INVALID_FORMAT)
        }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertextBytes = buffer.int
        if (ciphertextBytes < GCM_TAG_BYTES || ciphertextBytes != envelope.size - headerBytes) {
            return DecryptionResult.Invalid(InvalidReason.INVALID_FORMAT)
        }
        if (ciphertextBytes.toLong() - GCM_TAG_BYTES > MAX_IMPORT_FILE_BYTES) {
            return DecryptionResult.Invalid(InvalidReason.TOO_LARGE)
        }
        val providedPassphrase = passphrase?.takeIf(CharArray::isNotEmpty) ?: return DecryptionResult.PassphraseRequired
        if (providedPassphrase.size > MAX_PASSPHRASE_LENGTH) {
            return DecryptionResult.Invalid(InvalidReason.AUTHENTICATION_FAILED)
        }

        val header = envelope.copyOfRange(0, headerBytes)
        val ciphertext = envelope.copyOfRange(headerBytes, envelope.size)
        return try {
            val key = deriveKey(providedPassphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            val plaintext = cipher.doFinal(ciphertext)
            if (plaintext.size.toLong() > MAX_IMPORT_FILE_BYTES) {
                DecryptionResult.Invalid(InvalidReason.TOO_LARGE)
            } else {
                DecryptionResult.Success(plaintext)
            }
        } catch (_: AEADBadTagException) {
            DecryptionResult.Invalid(InvalidReason.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            DecryptionResult.Invalid(InvalidReason.INVALID_FORMAT)
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val keySpec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_BITS)
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        return try {
            SecretKeySpec(encoded, "AES")
        } finally {
            encoded.fill(0)
            keySpec.clearPassword()
        }
    }

    private fun ByteArray.startsWith(
        prefix: ByteArray,
    ): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
