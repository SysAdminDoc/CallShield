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
    fun `the vote type and the full number are part of the key`() {
        val entries = CommunityReportLedger.add(emptySet(), "+12122340101", "spam", now)

        assertFalse(CommunityReportLedger.contains(entries, "+12122340101", "not_spam"))
        // One number that is a prefix of another is still a different number.
        assertFalse(CommunityReportLedger.contains(entries, "+1212234010", "spam"))
    }

    @Test
    fun `a stamp from the future or an unreadable entry is dropped`() {
        val entries = setOf("+12122340101|spam|${now + 48 * hour}", "garbage", "+12122340102|spam|soon")

        assertEquals(emptySet<String>(), CommunityReportLedger.prune(entries, now))
    }

    @Test
    fun `the Worker's answers map to delivered, retry later, or give up`() {
        val duplicate = """{"error":"Duplicate report, already submitted"}"""
        val limited = """{"error":"Rate limited, please retry later"}"""

        assertEquals(CommunityContributor.ContributeOutcome.REPORTED_SPAM, CommunityContributor.resultFor(200, "spam", "", null).outcome)
        assertEquals(
            CommunityContributor.ContributeOutcome.REPORTED_NOT_SPAM,
            CommunityContributor.resultFor(200, "not_spam", "", null).outcome,
        )
        // An earlier attempt was stored; sending it again would store it twice.
        assertEquals(CommunityContributor.ContributeOutcome.REPORTED_SPAM, CommunityContributor.resultFor(429, "spam", duplicate, "300").outcome)
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
