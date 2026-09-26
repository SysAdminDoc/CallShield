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
        // A verdict, not a count: the answer carries no number of reports.
        assertEquals(0, result.reports)
    }

    @Test
    fun `skipcalls unknown category is a neutral report for Apple Support`() {
        val result =
            parseSkipCallsBody(
                """{"number":"8002752273","is_spam":true,"response_time_ms":0,"status_code":181,"status_description":"unknown"}""",
            )

        assertFalse(result.isSpam)
        assertEquals(RemoteLookupStatus.UNCATEGORIZED, result.status)
        assertFalse(result.status.isFallback)
        assertEquals(0, result.reports)
        assertEquals("", result.detail)
    }

    @Test
    fun `skipcalls unknown category is neutral for IRS and other labels`() {
        val irs =
            parseSkipCallsBody(
                """{"number":"8008291040","is_spam":true,"response_time_ms":0,"status_code":181,"status_description":"unknown"}""",
            )
        assertFalse(irs.isSpam)
        assertEquals(RemoteLookupStatus.UNCATEGORIZED, irs.status)

        for (category in listOf("other", "company", "")) {
            val result = parseSkipCallsBody("""{"is_spam":true,"status_description":"$category"}""")
            assertFalse("$category should not be flagged", result.isSpam)
            assertEquals(RemoteLookupStatus.UNCATEGORIZED, result.status)
        }
        assertEquals(
            RemoteLookupStatus.UNCATEGORIZED,
            parseSkipCallsBody("""{"is_spam":true}""").status,
        )
    }

    @Test
    fun `skipcalls explicitly categorized spam remains flagged`() {
        for (category in listOf("scam", "robocall", "telemarketer", "fraud")) {
            val result = parseSkipCallsBody("""{"is_spam":true,"status_description":"$category"}""")
            assertTrue("$category should be flagged", result.isSpam)
            assertEquals(RemoteLookupStatus.FOUND, result.status)
        }
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
