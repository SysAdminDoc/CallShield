package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.data.model.HotDataHealth
import com.sysadmindoc.callshield.data.model.HotDataHealthUpdate
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.util.HotFeedFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real feed files, through the production parser, sanitiser and apply rule, to
 * the state Protection Test shows.
 *
 * The case that matters is the 2026-08-24 one: the publisher kept serving a
 * perfectly reachable file with `count: 0`, no `cleared`, and a `generated`
 * stamp that never moved. The device refused it (correctly) and then reported
 * it as unreachable, which sent people to retry a sync that was working.
 */
class HotFeedHealthTest {
    private val now = HotFeedFreshness.publishedAtMillis("2026-09-21T12:00:00+00:00")
    private val parser = GitHubDataSource()

    private val currentFeed =
        """
        {"generated": "2026-09-20T12:00:00.000000+00:00", "input_report_digest": "d1",
         "count": 1, "ranges": [{"npanxx": "212555"}], "cleared": false}
        """.trimIndent()

    private val stalledEmptyFeed =
        """
        {"generated": "2026-09-05T12:01:53.867374+00:00", "input_report_digest": "e3b0",
         "count": 0, "ranges": []}
        """.trimIndent()

    private val deliberatelyClearedFeed =
        """
        {"generated": "2026-09-21T11:00:00.000000+00:00", "input_report_digest": "d2",
         "count": 0, "ranges": [], "cleared": true}
        """.trimIndent()

    private val freshEmptyUnclearedFeed =
        """
        {"generated": "2026-09-21T11:00:00.000000+00:00", "input_report_digest": "d3",
         "count": 0, "ranges": []}
        """.trimIndent()

    /** Mirrors HotDataSync.refresh for the ranges feed; null means the fetch failed. */
    private fun rangesObservation(body: String?): HotDataSync.FeedObservation {
        if (body == null) {
            return HotDataSync.FeedObservation(HotDataSync.HOT_RANGES_FEED, resolved = false, applied = false, empty = true)
        }
        val snapshot = parser.parseHotRangesSnapshotJson(body)
        val ranges = HotDataSync.sanitizeHotRanges(snapshot.data)
        return HotDataSync.FeedObservation(
            feed = HotDataSync.HOT_RANGES_FEED,
            resolved = true,
            applied = HotDataSync.shouldApplyFeed(ranges, snapshot.explicitlyCleared),
            empty = ranges.isEmpty(),
            generatedAt = snapshot.generatedAt,
            inputDigest = snapshot.inputDigest,
        )
    }

    /** What SettingsRepository stores for an update over [previous]. */
    private fun stored(
        update: HotDataHealthUpdate,
        previous: HotDataHealth = HotDataHealth(),
    ) = HotDataHealth(
        unavailableFeeds = update.unavailableFeeds,
        unreachableFeeds = update.unreachableFeeds,
        clearedFeeds = update.clearedFeeds,
        feedGeneratedAt =
            HotDataHealthUpdate.mergeFeedMetadata(previous.feedGeneratedAt, update.resolvedFeeds, update.feedGeneratedAt),
        feedDigests = HotDataHealthUpdate.mergeFeedMetadata(previous.feedDigests, update.resolvedFeeds, update.feedDigests),
    )

    private fun stateFor(body: String?): HotFeedFreshness.State = HotFeedFreshness.stateOf(stored(HotDataSync.healthUpdate(listOf(rangesObservation(body)))), now)

    @Test
    fun `the three fixtures reach the three states`() {
        assertEquals(HotFeedFreshness.State.CURRENT, stateFor(currentFeed))
        assertEquals(HotFeedFreshness.State.STALLED, stateFor(stalledEmptyFeed))
        assertEquals(HotFeedFreshness.State.UNREACHABLE, stateFor(null))
    }

    @Test
    fun `a refused empty feed keeps the worker retrying but is not called unreachable`() {
        val update = HotDataSync.healthUpdate(listOf(rangesObservation(stalledEmptyFeed)))
        assertEquals(setOf(HotDataSync.HOT_RANGES_FEED), update.unavailableFeeds)
        assertTrue(update.unreachableFeeds.isEmpty())
        assertEquals("2026-09-05T12:01:53.867374+00:00", update.feedGeneratedAt[HotDataSync.HOT_RANGES_FEED])
        assertEquals("e3b0", update.feedDigests[HotDataSync.HOT_RANGES_FEED])
    }

    @Test
    fun `a fresh refused feed is current, because the publisher is plainly alive`() {
        assertEquals(HotFeedFreshness.State.CURRENT, stateFor(freshEmptyUnclearedFeed))
    }

    @Test
    fun `a deliberate clear is recorded so it reads as a quiet day, not missing data`() {
        val update = HotDataSync.healthUpdate(listOf(rangesObservation(deliberatelyClearedFeed)))
        assertEquals(setOf(HotDataSync.HOT_RANGES_FEED), update.clearedFeeds)
        assertTrue(update.unavailableFeeds.isEmpty())
        assertEquals(HotFeedFreshness.State.CURRENT, stateFor(deliberatelyClearedFeed))
    }

    @Test
    fun `an applied non-empty feed is never recorded as cleared`() {
        val update = HotDataSync.healthUpdate(listOf(rangesObservation(currentFeed)))
        assertTrue(update.clearedFeeds.isEmpty())
        assertTrue(update.unavailableFeeds.isEmpty())
    }

    @Test
    fun `a feed that was read replaces its stamp, and one that was not keeps it`() {
        val previous =
            HotDataHealth(
                feedGeneratedAt =
                    mapOf(
                        HotDataSync.HOT_LIST_FEED to "2026-08-01T00:00:00+00:00",
                        HotDataSync.HOT_RANGES_FEED to "2026-08-01T00:00:00+00:00",
                    ),
            )
        // The hot list arrives without a `generated` field; the ranges fetch fails.
        val update =
            HotDataSync.healthUpdate(
                listOf(
                    HotDataSync.FeedObservation(HotDataSync.HOT_LIST_FEED, resolved = true, applied = true, empty = false),
                    rangesObservation(null),
                ),
            )
        val merged = stored(update, previous).feedGeneratedAt
        // A publisher that stops declaring its stamp is no longer judged by the old one.
        assertFalse(merged.containsKey(HotDataSync.HOT_LIST_FEED))
        // A transient failure does not erase what is known about the publisher.
        assertEquals("2026-08-01T00:00:00+00:00", merged[HotDataSync.HOT_RANGES_FEED])
    }

    @Test
    fun `a bundled snapshot after a failed fetch is unreachable and leaves the stored stamp`() {
        // Production mapping: the fetch failed, so HotDataSync filled an empty
        // store from the bundled asset, which today is an empty cleared feed.
        val bundled =
            HotDataSync
                .FeedLoadResult(
                    data = emptyList<String>(),
                    resolved = true,
                    explicitlyCleared = true,
                    generatedAt = "2026-09-05T12:01:53.867374+00:00",
                    failure = java.io.IOException("unable to resolve host"),
                ).observe(HotDataSync.HOT_RANGES_FEED, applied = true, empty = true)
        val update = HotDataSync.healthUpdate(listOf(bundled))

        assertEquals(setOf(HotDataSync.HOT_RANGES_FEED), update.unreachableFeeds)
        assertTrue(update.clearedFeeds.isEmpty())
        assertTrue(update.resolvedFeeds.isEmpty())

        val previous = HotDataHealth(feedGeneratedAt = mapOf(HotDataSync.HOT_RANGES_FEED to "2026-09-20T12:00:00+00:00"))
        val health = stored(update, previous)
        assertEquals("2026-09-20T12:00:00+00:00", health.feedGeneratedAt[HotDataSync.HOT_RANGES_FEED])
        assertEquals(HotFeedFreshness.State.UNREACHABLE, HotFeedFreshness.stateOf(health, now))
    }

    @Test
    fun `a feed read from the network is observed as a network read`() {
        val read =
            HotDataSync
                .FeedLoadResult(data = listOf("212555"), resolved = true, generatedAt = "2026-09-20T12:00:00+00:00")
                .observe(HotDataSync.HOT_RANGES_FEED, applied = true, empty = false)
        assertTrue(read.fromNetwork)
    }

    @Test
    fun `an install that has not refreshed since the split reads unavailable as unreachable`() {
        val legacy = HotDataHealth(unavailableFeeds = setOf(HotDataSync.HOT_LIST_FEED), unreachableFeeds = null)
        assertEquals(HotFeedFreshness.State.UNREACHABLE, HotFeedFreshness.stateOf(legacy, now))
    }
}
