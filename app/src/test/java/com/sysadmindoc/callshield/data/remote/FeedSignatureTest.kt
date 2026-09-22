package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class FeedSignatureTest {
    private val pair = newKeyPair()
    private val body = "{\r\n  \"count\": 1,\r\n  \"numbers\": [\"+12125550101\"]\r\n}".toByteArray(Charsets.UTF_8)

    @Test
    fun `a signature by a trusted key verifies, trailing newline and all`() {
        assertTrue(FeedSignature.verifies(body, sign(body) + "\n", listOf(pair.public)))
    }

    @Test
    fun `flipping one byte of a signed feed is refused`() {
        val signature = sign(body)
        val tampered = body.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }

        assertFalse(FeedSignature.verifies(tampered, signature, listOf(pair.public)))
    }

    @Test
    fun `rewriting line endings is refused, because the signature covers exact bytes`() {
        val signature = sign(body)
        val relined = String(body, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)

        assertFalse(FeedSignature.verifies(relined, signature, listOf(pair.public)))
    }

    @Test
    fun `another key, garbage, and an empty file are all refused`() {
        val stranger = newKeyPair()

        assertFalse(FeedSignature.verifies(body, sign(body, stranger.private), listOf(pair.public)))
        assertFalse(FeedSignature.verifies(body, "not base64!", listOf(pair.public)))
        assertFalse(FeedSignature.verifies(body, "", listOf(pair.public)))
        assertFalse(FeedSignature.verifies(body, "AAAA", listOf(pair.public)))
    }

    @Test
    fun `the app trusts a signing key and a backup, both P-256`() {
        assertEquals(2, FeedSignature.TRUSTED_KEYS.size)
        FeedSignature.TRUSTED_KEYS.forEach { encoded ->
            val key = FeedSignature.decodePublicKey(encoded) as ECPublicKey
            assertEquals(256, key.params.order.bitLength())
        }
    }

    @Test
    fun `the feeds published in this repository verify under the app's keys`() {
        // The positive control for the whole scheme: what devices download today is accepted.
        GitHubDataSource.SIGNED_FEED_PATHS.forEach { path ->
            val feed = File("..", path)
            val signature = File("..", "$path.sig")
            assertTrue("$path is missing", feed.isFile)
            assertTrue("$path.sig is missing; run scripts/feed_signing.py sign", signature.isFile)
            assertTrue("$path doesn't verify", FeedSignature.verifies(feed.readBytes(), signature.readText()))
        }
    }

    private fun sign(
        data: ByteArray,
        key: PrivateKey = pair.private,
    ): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(data)
                sign()
            },
        )

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
