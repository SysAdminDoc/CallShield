package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.JsonEncodingException
import com.sysadmindoc.callshield.data.remote.GitHubFeedFailureReason
import com.sysadmindoc.callshield.data.remote.GitHubFeedHttpException
import com.sysadmindoc.callshield.data.remote.GitHubFeedValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Home showed the sync's exception text, such as
 * "Sync unavailable (Unable to resolve host "raw.githubusercontent.com": No address associated with hostname)".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFailureTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each failure maps to its kind`() {
        val cases =
            mapOf(
                UnknownHostException("Unable to resolve host \"raw.githubusercontent.com\"") to SyncFailureKind.DNS,
                ConnectException("Failed to connect to raw.githubusercontent.com/185.199.108.133:443") to SyncFailureKind.OFFLINE,
                SocketTimeoutException("timeout") to SyncFailureKind.OFFLINE,
                SSLPeerUnverifiedException("Hostname raw.githubusercontent.com not verified") to SyncFailureKind.SECURE_CONNECTION,
                GitHubFeedHttpException(404, "Not Found") to SyncFailureKind.HTTP,
                Exception("HTTP 503: Service Unavailable") to SyncFailureKind.HTTP,
                GitHubFeedValidationException(GitHubFeedFailureReason.SIGNATURE, "signature did not verify") to SyncFailureKind.SIGNATURE,
                GitHubFeedValidationException(GitHubFeedFailureReason.INVALID_SCHEMA, "bad schema") to SyncFailureKind.PARSE,
                // An IOException subclass, so it must not read as a network failure.
                JsonEncodingException("Use JsonReader.setLenient(true)") to SyncFailureKind.PARSE,
                SpamFeedManifestRejectedException("Database updated date is invalid") to SyncFailureKind.PARSE,
                Exception("Empty response body") to SyncFailureKind.PARSE,
                RuntimeException("wrapped", UnknownHostException("raw.githubusercontent.com")) to SyncFailureKind.DNS,
                IllegalStateException("No commits found") to SyncFailureKind.UNKNOWN,
            )

        cases.forEach { (error, kind) -> assertEquals(error.toString(), kind, SyncFailureText.kind(error)) }
        assertEquals(SyncFailureKind.UNKNOWN, SyncFailureText.kind(null))
    }

    @Test
    fun `sentences carry no exception text`() {
        val dns = SyncFailureText.sentence(context, UnknownHostException("Unable to resolve host \"raw.githubusercontent.com\""))
        val http = SyncFailureText.sentence(context, GitHubFeedHttpException(404, "Not Found"))

        assertFalse(dns, dns.contains("githubusercontent") || dns.contains("resolve host"))
        assertEquals("The update server answered with error 404. Try again later.", http)
        assertEquals(
            "Sync failed. Couldn't look up the update server. Check that you're online and that Private DNS isn't blocking it.",
            context.getString(com.sysadmindoc.callshield.R.string.sync_failed, dns),
        )
    }

    @Test
    fun `every kind has its own sentence`() {
        val sentences =
            listOf(
                ConnectException("x"),
                UnknownHostException("x"),
                SSLPeerUnverifiedException("x"),
                GitHubFeedHttpException(500, "x"),
                GitHubFeedValidationException(GitHubFeedFailureReason.SIGNATURE, "x"),
                GitHubFeedValidationException(GitHubFeedFailureReason.OVERSIZE, "x"),
                IllegalStateException("x"),
            ).map { SyncFailureText.sentence(context, it) }

        assertEquals(sentences.size, sentences.toSet().size)
        assertTrue(sentences.none { it.isBlank() })
    }

    @Test
    fun `refused requests are not retried and everything else is`() {
        listOf(400, 401, 403, 404).forEach { code -> assertFalse(SyncFailureText.shouldRetry(GitHubFeedHttpException(code, "x"))) }
        assertFalse(SyncFailureText.shouldRetry(Exception("HTTP 404: Not Found")))
        assertTrue(SyncFailureText.shouldRetry(GitHubFeedHttpException(429, "Too Many Requests")))
        assertTrue(SyncFailureText.shouldRetry(UnknownHostException("x")))
        assertTrue(SyncFailureText.shouldRetry(null))
    }
}
