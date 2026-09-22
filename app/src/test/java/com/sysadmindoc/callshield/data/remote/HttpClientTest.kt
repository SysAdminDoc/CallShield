package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLPeerUnverifiedException

class HttpClientTest {
    @Test
    fun `shared client enforces configured certificate pins`() {
        HttpClient.pinnedEndpointPins.keys.forEach { host ->
            try {
                HttpClient.shared.certificatePinner.check(host, emptyList())
            } catch (_: Exception) {
                return@forEach
            }
            throw AssertionError("$host should require a matching certificate pin")
        }

        HttpClient.shared.certificatePinner.check("unlisted.example", emptyList())
    }

    @Test
    fun `all network endpoint hosts are pinned`() {
        assertEquals(
            setOf(
                "api.github.com",
                "raw.githubusercontent.com",
                "callshield-reports.snafumatthew.workers.dev",
                "spam.skipcalls.app",
                "urlhaus-api.abuse.ch",
            ),
            HttpClient.pinnedEndpointPins.keys,
        )
    }

    @Test
    fun `a pin or hostname mismatch is told apart from ordinary network trouble`() {
        val pinFailure = SSLPeerUnverifiedException("Certificate pinning failure!")
        assertTrue(HttpClient.isCertificateTrustFailure(pinFailure))
        // Result.failure usually wraps the transport error one level down.
        assertTrue(HttpClient.isCertificateTrustFailure(IOException("fetch failed", pinFailure)))
        assertFalse(HttpClient.isCertificateTrustFailure(SocketTimeoutException("slow network")))
        assertFalse(HttpClient.isCertificateTrustFailure(IOException("HTTP 503")))
        assertFalse(HttpClient.isCertificateTrustFailure(null))
    }

    @Test
    fun `the raw feed host pins Let's Encrypt roots, never an intermediate`() {
        val rawPins = HttpClient.pinnedEndpointPins.getValue("raw.githubusercontent.com")
        // ISRG Root X1 and Root YR are what the device's verified chain ends in today.
        assertTrue(rawPins.contains("sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="))
        assertTrue(rawPins.contains("sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="))
        // The retired leaf and the LE R12 intermediate that broke on 2026-08-02.
        assertFalse(rawPins.contains("sha256/W+jBdq3o4qj8cXXBURwKqofJk8BG59NEPXOEgMh53sA="))
        assertFalse(rawPins.contains("sha256/kZwN96eHtZftBWrOZUsd6cA4es80n3NzSk/XtYz2EqQ="))
    }

    @Test
    fun `pins use OkHttp sha256 pin format and include backup coverage`() {
        val pinPattern = Regex("""sha256/[A-Za-z0-9+/]{43}=""")

        HttpClient.pinnedEndpointPins.forEach { (host, pins) ->
            assertTrue("$host should include at least two pins", pins.size >= 2)
            assertEquals("$host should not duplicate pins", pins.size, pins.distinct().size)
            pins.forEach { pin ->
                assertTrue("$host has invalid pin $pin", pinPattern.matches(pin))
            }
        }
    }
}
