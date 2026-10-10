package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.CommunityReportHistory.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CommunityReportHistoryTest {
    private val now = 1_760_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `a report keeps the date it was made through every delivery change`() {
        val queued = CommunityReportHistory.record(emptySet(), "r1", "+12122340101", "spam", Delivery.QUEUED, now)
        val sent = CommunityReportHistory.record(queued, "r1", "+12122340101", "spam", Delivery.SENT, now + day)

        val entry = CommunityReportHistory.list(sent, now + day).single()
        assertEquals(now, entry.reportedAt)
        assertEquals(Delivery.SENT, entry.delivery)
    }

    @Test
    fun `the list is newest first and forgets reports older than 90 days`() {
        var entries = CommunityReportHistory.record(emptySet(), "old", "+12122340101", "spam", Delivery.SENT, now)
        entries = CommunityReportHistory.record(entries, "mid", "+12122340102", "robocall", Delivery.SENT, now + day)
        entries = CommunityReportHistory.record(entries, "new", "+12122340103", "not_spam", Delivery.QUEUED, now + 2 * day)

        assertEquals(listOf("new", "mid", "old"), CommunityReportHistory.list(entries, now + 2 * day).map { it.id })
        assertEquals(listOf("new", "mid"), CommunityReportHistory.list(entries, now + 90 * day).map { it.id })
    }

    @Test
    fun `only the newest reports are kept past the cap`() {
        var entries = emptySet<String>()
        repeat(CommunityReportHistory.MAX_ENTRIES + 5) { i ->
            entries = CommunityReportHistory.record(entries, "r$i", "+1212234${1000 + i}", "spam", Delivery.SENT, now + i)
        }

        val kept = CommunityReportHistory.list(entries, now + 1_000)
        assertEquals(CommunityReportHistory.MAX_ENTRIES, kept.size)
        assertEquals("r5", kept.last().id)
    }

    @Test
    fun `a spam report can be corrected until a not-spam report follows it`() {
        val spam = CommunityReportHistory.Entry("r1", "+12122340101", "robocall", now, Delivery.SENT)
        val refused = spam.copy(id = "r2", delivery = Delivery.NOT_SENT)
        val notSpam = CommunityReportHistory.Entry("r3", "+12122340101", CommunityReportHistory.NOT_SPAM, now + day, Delivery.QUEUED)
        val olderNotSpam = notSpam.copy(id = "r4", reportedAt = now - day)

        assertTrue(CommunityReportHistory.canCorrect(spam, listOf(spam, olderNotSpam)))
        assertFalse(CommunityReportHistory.canCorrect(spam, listOf(spam, notSpam)))
        assertFalse("nothing went out to correct", CommunityReportHistory.canCorrect(refused, listOf(refused)))
        assertFalse(CommunityReportHistory.canCorrect(notSpam, listOf(notSpam)))
        assertTrue(
            "a not-spam report that never went out corrects nothing",
            CommunityReportHistory.canCorrect(spam, listOf(spam, notSpam.copy(delivery = Delivery.NOT_SENT))),
        )
    }

    @Test
    fun `the number screen finds the newest spam report under any spelling of the number`() {
        val older = CommunityReportHistory.Entry("r1", "+12122340101", "spam", now - day, Delivery.SENT)
        val newer = older.copy(id = "r2", type = "robocall", reportedAt = now, delivery = Delivery.QUEUED)
        val forms = listOf("2122340101", "+12122340101")

        assertEquals("r2", CommunityReportHistory.lastSpamReport(listOf(newer, older), forms)?.id)
        assertNull("another number", CommunityReportHistory.lastSpamReport(listOf(newer), listOf("+12122340102")))
        assertNull(
            "a not-spam vote isn't a spam report",
            CommunityReportHistory.lastSpamReport(listOf(newer.copy(type = CommunityReportHistory.NOT_SPAM)), forms),
        )
        assertEquals(
            "a report that never went out is passed over",
            "r1",
            CommunityReportHistory.lastSpamReport(listOf(newer.copy(delivery = Delivery.NOT_SENT), older), forms)?.id,
        )
    }

    @Test
    fun `a damaged entry is skipped`() {
        assertNull(CommunityReportHistory.decode("r1|+12122340101|spam|soon|SENT"))
        assertNull(CommunityReportHistory.decode("r1|+12122340101|spam|1|LOST"))
        assertNull(CommunityReportHistory.decode("|+12122340101|spam|1|SENT"))
        assertEquals(
            emptyList<String>(),
            CommunityReportHistory.list(setOf("r1|+12122340101|spam", "garbage"), now).map { it.id },
        )
    }
}
