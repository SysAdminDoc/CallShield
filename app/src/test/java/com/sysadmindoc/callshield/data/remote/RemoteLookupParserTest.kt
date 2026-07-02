package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLookupParserTest {
    @Test
    fun `skipcalls parser handles normal spam response`() {
        val result = parseSkipCallsBody("""{"spam":true,"reports":4}""")

        assertTrue(result.isSpam)
        assertEquals(4, result.reports)
        assertEquals(RemoteLookupStatus.FOUND, result.status)
    }

    @Test
    fun `skipcalls parser handles malformed response as clean fallback`() {
        val result = parseSkipCallsBody("""{"spam":""")

        assertFalse(result.isSpam)
        assertEquals(0, result.reports)
        assertEquals(RemoteLookupStatus.CLEAN, result.status)
    }

    @Test
    fun `web lookup parser extracts bounded notes from normal html`() {
        val body = listOf(
            "<html>",
            """<div class="comment">Repeated vehicle warranty calls every morning.</div>""",
            "<p>12 reports</p>",
            "</html>",
        ).joinToString("\n")

        val result = WebLookup.parseLookupBody(body)

        assertEquals(12, result.spamReports)
        assertEquals(listOf("Repeated vehicle warranty calls every morning."), result.communityNotes)
        assertEquals(RemoteLookupStatus.FOUND, result.status)
    }

    @Test
    fun `number type parser keeps malformed payload unknown without crashing`() {
        val result = NumberTypeChecker.parseNumberTypeBody("""{"type":{""")

        assertEquals(NumberTypeChecker.NumberLineType.UNKNOWN, result.lineType)
        assertEquals("", result.carrier)
        assertEquals(RemoteLookupStatus.CLEAN, result.status)
    }

    @Test
    fun `number type parser detects voip normal response`() {
        val body = listOf(
            "{",
            """"type": {"type": "VoIP", "is_prepaid": false},""",
            """"carrier": {"name": "Example Voice"},""",
            """"country_code": "US"""",
            "}",
        ).joinToString("\n")

        val result = NumberTypeChecker.parseNumberTypeBody(body)

        assertEquals(NumberTypeChecker.NumberLineType.VOIP, result.lineType)
        assertEquals("Example Voice", result.carrier)
        assertEquals("US", result.country)
        assertEquals(RemoteLookupStatus.FOUND, result.status)
    }
}
