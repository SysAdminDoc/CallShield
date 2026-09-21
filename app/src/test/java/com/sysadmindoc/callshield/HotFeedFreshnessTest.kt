package com.sysadmindoc.callshield

import com.sysadmindoc.callshield.util.HotFeedFreshness
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The three hot feeds are the only protection data that turns over in minutes,
 * and until now the client recorded one bit about them: did the last refresh
 * succeed. That collapsed two very different failures into one message.
 *
 * Between 2026-08-24 and 2026-09-05 the report queue stopped being consumed and
 * all three feeds published `count: 0` with a `generated` timestamp that never
 * moved. Every device said "hot data unavailable", which reads as a network
 * problem and sends the user to retry a sync that was already working.
 */
class HotFeedFreshnessTest {
    private val now = 1_788_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `a feed the device could not fetch is unreachable`() {
        assertEquals(
            HotFeedFreshness.State.UNREACHABLE,
            HotFeedFreshness.classify(unreachable = true, publishedAtMillis = now, now = now),
        )
    }

    @Test
    fun `an unfetched feed stays unreachable however fresh its last timestamp was`() {
        // The timestamp is from an earlier read, so it says nothing about this
        // refresh. Reachability wins.
        assertEquals(
            HotFeedFreshness.State.UNREACHABLE,
            HotFeedFreshness.classify(unreachable = true, publishedAtMillis = now - 400 * day, now = now),
        )
    }

    @Test
    fun `a recently generated feed is current`() {
        assertEquals(
            HotFeedFreshness.State.CURRENT,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = now - day, now = now),
        )
    }

    @Test
    fun `a reachable feed whose publisher stopped is stalled, not unreachable`() {
        assertEquals(
            HotFeedFreshness.State.STALLED,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = now - 11 * day, now = now),
        )
    }

    @Test
    fun `the stall threshold is exclusive at its boundary`() {
        val threshold = HotFeedFreshness.DEFAULT_STALL_THRESHOLD_MILLIS
        assertEquals(
            HotFeedFreshness.State.CURRENT,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = now - threshold, now = now),
        )
        assertEquals(
            HotFeedFreshness.State.STALLED,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = now - threshold - 1, now = now),
        )
    }

    @Test
    fun `a feed that does not declare when it was generated is not judged stalled`() {
        // Legacy array payloads carry no envelope. Treating a missing timestamp
        // as infinitely old would report every one of them as stalled.
        assertEquals(
            HotFeedFreshness.State.CURRENT,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = 0L, now = now),
        )
    }

    @Test
    fun `a clock skewed behind the publisher is not evidence of a stall`() {
        assertEquals(
            HotFeedFreshness.State.CURRENT,
            HotFeedFreshness.classify(unreachable = false, publishedAtMillis = now + 5 * day, now = now),
        )
    }

    @Test
    fun `iso timestamps parse and anything else yields no opinion`() {
        assertEquals(
            1_787_628_549_000L,
            HotFeedFreshness.publishedAtMillis("2026-08-25T03:29:09Z"),
        )
        assertEquals(0L, HotFeedFreshness.publishedAtMillis(null))
        assertEquals(0L, HotFeedFreshness.publishedAtMillis(""))
        assertEquals(0L, HotFeedFreshness.publishedAtMillis("   "))
        assertEquals(0L, HotFeedFreshness.publishedAtMillis("2026-08-25"))
        assertEquals(0L, HotFeedFreshness.publishedAtMillis("not a timestamp"))
    }

    @Test
    fun `the publisher's own offset format parses, not just Z`() {
        // scripts/pipeline_io.py writes Python isoformat(), so every real feed
        // carries "+00:00" and microseconds. Instant.parse only accepts an offset
        // from Java 12, which would read every real feed as "no opinion" on the
        // Java 11 based runtimes of Android 10 to 13.
        assertEquals(
            1_788_609_713_867L,
            HotFeedFreshness.publishedAtMillis("2026-09-05T12:01:53.867374+00:00"),
        )
        assertEquals(
            1_788_609_713_000L,
            HotFeedFreshness.publishedAtMillis("2026-09-05T08:01:53-04:00"),
        )
    }

    @Test
    fun `the summary reports the most actionable state across feeds`() {
        assertEquals(HotFeedFreshness.State.CURRENT, HotFeedFreshness.worst(emptyList()))
        assertEquals(
            HotFeedFreshness.State.CURRENT,
            HotFeedFreshness.worst(listOf(HotFeedFreshness.State.CURRENT, HotFeedFreshness.State.CURRENT)),
        )
        assertEquals(
            HotFeedFreshness.State.STALLED,
            HotFeedFreshness.worst(listOf(HotFeedFreshness.State.CURRENT, HotFeedFreshness.State.STALLED)),
        )
        // A device that cannot read a feed cannot know whether the publisher is
        // also stalled, so unreachable outranks stalled.
        assertEquals(
            HotFeedFreshness.State.UNREACHABLE,
            HotFeedFreshness.worst(
                listOf(HotFeedFreshness.State.STALLED, HotFeedFreshness.State.UNREACHABLE),
            ),
        )
    }
}
