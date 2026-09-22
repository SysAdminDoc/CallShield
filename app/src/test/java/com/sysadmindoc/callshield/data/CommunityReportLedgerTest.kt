package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CommunityReportLedgerTest {
    private val now = 1_790_000_000_000L
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun `a report is remembered for a day and then forgotten`() {
        val entries = CommunityReportLedger.add(emptySet(), "+12122340101", "spam", now - 23 * hour)

        assertTrue(CommunityReportLedger.contains(CommunityReportLedger.prune(entries, now), "+12122340101", "spam"))
        assertFalse(CommunityReportLedger.contains(CommunityReportLedger.prune(entries, now + 2 * hour), "+12122340101", "spam"))
    }

    @Test
    fun `the vote and the full number are part of the key`() {
        val entries = CommunityReportLedger.add(emptySet(), "+12122340101", "spam", now)

        assertFalse(CommunityReportLedger.contains(entries, "+12122340101", "not_spam"))
        // One number that is a prefix of another is still a different number.
        assertFalse(CommunityReportLedger.contains(entries, "+1212234010", "spam"))
    }

    @Test
    fun `every spam category is one vote, and a correction or a text is another`() {
        listOf("spam", "robocall", "scam", "telemarketer", "debt_collector", "ai_voice", "unknown").forEach {
            assertEquals(it, "spam", CommunityReportLedger.voteOf(it))
        }
        assertEquals("not_spam", CommunityReportLedger.voteOf("not_spam"))
        assertEquals("sms_spam", CommunityReportLedger.voteOf("sms_spam"))
    }

    @Test
    fun `releasing a claim drops only that number and vote`() {
        var entries = CommunityReportLedger.add(emptySet(), "+12122340101", "spam", now)
        entries = CommunityReportLedger.add(entries, "+12122340101", "not_spam", now)
        entries = CommunityReportLedger.add(entries, "+12122340102", "spam", now)

        val released = CommunityReportLedger.remove(entries, "+12122340101", "spam")

        assertFalse(CommunityReportLedger.contains(released, "+12122340101", "spam"))
        assertTrue(CommunityReportLedger.contains(released, "+12122340101", "not_spam"))
        assertTrue(CommunityReportLedger.contains(released, "+12122340102", "spam"))
    }

    @Test
    fun `the report carries its id to the Worker`() {
        val json = CommunityContributor.buildReportJson("+12122340101", "spam", null, reportId = "8c0d6e1a-2b4f-4c3d-9e8f-0a1b2c3d4e5f")

        assertTrue(json, "\"report_id\":\"8c0d6e1a-2b4f-4c3d-9e8f-0a1b2c3d4e5f\"" in json)
    }

    @Test
    fun `a stamp from the future or an unreadable entry is dropped`() {
        val entries = setOf("+12122340101|spam|${now + 48 * hour}", "garbage", "+12122340102|spam|soon")

        assertEquals(emptySet<String>(), CommunityReportLedger.prune(entries, now))
    }

    @Test
    fun `the Worker's answers map to delivered, retry later, or give up`() {
        val stored = """{"error":"Duplicate report, already submitted","already_stored":true}"""
        val bareDuplicate = """{"error":"Duplicate report, already submitted"}"""
        val limited = """{"error":"Rate limited, please retry later"}"""

        assertEquals(CommunityContributor.ContributeOutcome.REPORTED_SPAM, CommunityContributor.resultFor(200, "spam", "", null).outcome)
        assertEquals(
            CommunityContributor.ContributeOutcome.REPORTED_NOT_SPAM,
            CommunityContributor.resultFor(200, "not_spam", "", null).outcome,
        )
        // An earlier attempt was stored; sending it again would store it twice.
        assertEquals(CommunityContributor.ContributeOutcome.REPORTED_SPAM, CommunityContributor.resultFor(429, "spam", stored, "300").outcome)
        // The deployed Worker marks a duplicate before storing, so its bare answer
        // may mean the report was lost: try again later instead of calling it sent.
        assertTrue(CommunityContributor.resultFor(429, "spam", bareDuplicate, "300").outcome.isTransient)
        val rateLimited = CommunityContributor.resultFor(429, "spam", limited, "120")
        assertEquals(CommunityContributor.ContributeOutcome.RATE_LIMITED, rateLimited.outcome)
        assertEquals(120, rateLimited.retryAfterSeconds)
        assertTrue(rateLimited.outcome.isTransient)
        assertEquals(CommunityContributor.ContributeOutcome.INVALID_NUMBER, CommunityContributor.resultFor(400, "spam", "", null).outcome)
        assertTrue(CommunityContributor.resultFor(503, "spam", "", "300").outcome.isTransient)
    }

    @Test
    fun `the ledger keeps only its newest entries`() {
        var entries = emptySet<String>()
        repeat(CommunityReportLedger.MAX_ENTRIES + 10) { index ->
            entries = CommunityReportLedger.add(entries, "+1212234%04d".format(index), "spam", now + index)
        }

        assertEquals(CommunityReportLedger.MAX_ENTRIES, entries.size)
        assertFalse(CommunityReportLedger.contains(entries, "+12122340000", "spam"))
        assertTrue(CommunityReportLedger.contains(entries, "+1212234%04d".format(CommunityReportLedger.MAX_ENTRIES + 9), "spam"))
    }
}
