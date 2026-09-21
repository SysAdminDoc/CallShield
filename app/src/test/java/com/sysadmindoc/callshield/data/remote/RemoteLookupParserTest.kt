package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLookupParserTest {
    @Test
    fun `skipcalls parser reads the real spam response`() {
        // Shape returned by spam.skipcalls.app/check/<digits> on 2026-09-21.
        val result =
            parseSkipCallsBody(
                """{"number":"8333041447","is_spam":true,"response_time_ms":0,"status_code":110,"status_description":"scam"}""",
            )

        assertTrue(result.isSpam)
        assertEquals("Flagged as scam", result.detail)
        assertEquals(RemoteLookupStatus.FOUND, result.status)
    }

    @Test
    fun `skipcalls parser does not print an unknown category`() {
        val result =
            parseSkipCallsBody(
                """{"number":"8002752273","is_spam":true,"response_time_ms":0,"status_code":181,"status_description":"unknown"}""",
            )

        assertTrue(result.isSpam)
        assertEquals("Flagged as spam", result.detail)
    }

    @Test
    fun `skipcalls parser reads the real clean response`() {
        val result = parseSkipCallsBody("""{"number":"8443218090","is_spam":false,"response_time_ms":0.01}""")

        assertFalse(result.isSpam)
        assertEquals(RemoteLookupStatus.CLEAN, result.status)
    }

    @Test
    fun `a page without the lookup marker is unavailable, never clean`() {
        // What a parked domain served with HTTP 200 looked like (www.whocalledme.com, 2026-09-21).
        val parked =
            """<!DOCTYPE html><html><head><script>window.onload=function(){window.location.href="/lander"}</script></head></html>"""

        val result = parseSkipCallsBody(parked)

        assertFalse(result.isSpam)
        assertEquals(RemoteLookupStatus.UNAVAILABLE, result.status)
        assertTrue(result.status.isFallback)
    }

    @Test
    fun `a JSON body with some other shape is unavailable too`() {
        val result = parseSkipCallsBody("""{"spam":true,"reports":4}""")

        assertFalse(result.isSpam)
        assertEquals(RemoteLookupStatus.UNAVAILABLE, result.status)
    }

    @Test
    fun `skipcalls parser handles malformed response as parse error fallback`() {
        val result = parseSkipCallsBody("""{"spam":""")

        assertFalse(result.isSpam)
        assertEquals(0, result.reports)
        assertEquals(RemoteLookupStatus.PARSE_ERROR, result.status)
    }

    @Test
    fun `remote lookup status distinguishes rate limits from generic http errors`() {
        assertEquals(RemoteLookupStatus.RATE_LIMITED, RemoteLookupStatus.fromHttpCode(429))
        assertEquals(RemoteLookupStatus.HTTP_ERROR, RemoteLookupStatus.fromHttpCode(500))
        assertTrue(RemoteLookupStatus.RATE_LIMITED.isFallback)
    }
}
