package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FalsePositiveReportTest {
    private val flagged =
        SpamCheckResult(
            isSpam = true,
            matchSource = "keyword",
            type = "sms_spam",
            description = "Keyword: 'package held' in \"Hi it's Mom, your package is held, call 2125550143\"",
            confidence = 87,
        )

    @Test
    fun `the report carries what it takes to find the rule`() {
        val text = FalsePositiveReport.text("+12125550101", flagged, "1.7.39 (67)", 41, "2026-09-20")

        assertTrue(text, "Number: +12125550101" in text)
        assertTrue(text, "Decided by: ${flagged.reasonCode.wireValue}" in text)
        assertTrue(text, "Confidence: 87%" in text)
        assertTrue(text, "App: 1.7.39 (67)" in text)
        assertTrue(text, "Database: version 41, updated 2026-09-20" in text)
        assertTrue(text, FalsePositiveReport.DISCUSSION_URL in text)
    }

    @Test
    fun `nothing from the message, a contact or the call log goes in`() {
        val text = FalsePositiveReport.text("+12125550101", flagged, "1.7.39 (67)", 41, "2026-09-20")

        assertFalse(text, "Mom" in text)
        assertFalse(text, "package" in text)
        assertFalse(text, "2125550143" in text)
        assertEquals(7, text.lines().size)
    }

    @Test
    fun `a phone that has never synced says so`() {
        assertTrue(FalsePositiveReport.text("+12125550101", flagged, "1.7.39 (67)", null, null).contains("Database: not synced yet"))
    }
}
