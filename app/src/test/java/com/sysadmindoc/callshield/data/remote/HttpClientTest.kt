package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
                "phoneblock.net",
                "www.whocalledme.com",
                "api.opencnam.com",
                "urlhaus-api.abuse.ch",
            ),
            HttpClient.pinnedEndpointPins.keys,
        )
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
