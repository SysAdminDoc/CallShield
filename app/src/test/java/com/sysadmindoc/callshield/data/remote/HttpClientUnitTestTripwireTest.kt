package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.CommunityContributor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * No unit test may reach a live server. The report Worker commits every report
 * it accepts to the public repo, and a receiver test posted two on every run
 * for two months before anyone noticed. These use DNS lookups and a reserved
 * `.invalid` host, so even a build without the tripwire sends no request.
 */
class HttpClientUnitTestTripwireTest {
    @Test
    fun `the unit-test task turns the tripwire on`() {
        assertEquals("true", System.getProperty(HttpClient.UNIT_TEST_PROPERTY))
    }

    @Test
    fun `the shared client won't resolve the report Worker`() {
        val host = CommunityContributor.WORKER_URL.toHttpUrl().host

        val refused = assertThrows(UnknownHostException::class.java) { HttpClient.shared.dns.lookup(host) }
        assertTrue(refused.message, refused.message.orEmpty().contains("must not reach $host"))
    }

    @Test
    fun `a request to a real host fails before it leaves the machine`() {
        val request = Request.Builder().url("https://tripwire.invalid/report").build()

        val refused =
            assertThrows(UnknownHostException::class.java) {
                HttpClient.shared
                    .newCall(request)
                    .execute()
                    .close()
            }
        assertTrue(refused.message, refused.message.orEmpty().contains("must not reach tripwire.invalid"))
    }

    @Test
    fun `clients derived from the shared one keep the tripwire`() {
        val derived =
            HttpClient.shared
                .newBuilder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build()

        assertThrows(UnknownHostException::class.java) { derived.dns.lookup("raw.githubusercontent.com") }
    }

    @Test
    fun `loopback still resolves for local test servers`() {
        assertTrue(
            HttpClient.shared.dns
                .lookup("localhost")
                .all { it.isLoopbackAddress },
        )
    }
}
