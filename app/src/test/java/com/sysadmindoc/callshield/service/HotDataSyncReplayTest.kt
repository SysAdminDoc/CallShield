package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A signature proves who made a feed, not that it's the latest. Whoever can
 * serve files (a mirror, or anyone once pinning fails) could hand back a
 * genuine older copy, such as a past `cleared: true`, and wipe the phone's
 * trending rows. The real refresh path, over a real repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotDataSyncReplayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)
    private val feeds = ScriptedHotFeeds()

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `an older signed copy replayed after a newer one can't wipe the trending rows`() {
        feeds.hotList =
            HotFeedSnapshot(
                listOf(HotNumber(number = "+12125550101", type = "robocall", description = "")),
                generatedAt = "2026-09-21T10:00:00+00:00",
            )
        refresh()
        assertEquals(setOf("+12125550101"), hotRows())

        feeds.hotList = HotFeedSnapshot(emptyList(), explicitlyCleared = true, generatedAt = "2026-09-05T12:00:00+00:00")
        refresh()

        assertEquals(setOf("+12125550101"), hotRows())
        val health = runBlocking { fixture.repository.readHotDataHealth() }
        assertTrue(health.refusedFeeds.toString(), HotDataSync.HOT_LIST_FEED in health.refusedFeeds)
        assertEquals("2026-09-21T10:00:00+00:00", health.feedGeneratedAt[HotDataSync.HOT_LIST_FEED])
    }

    @Test
    fun `a newer clear still applies`() {
        feeds.hotList =
            HotFeedSnapshot(
                listOf(HotNumber(number = "+12125550101", type = "robocall", description = "")),
                generatedAt = "2026-09-21T10:00:00+00:00",
            )
        refresh()

        feeds.hotList = HotFeedSnapshot(emptyList(), explicitlyCleared = true, generatedAt = "2026-09-21T11:00:00+00:00")
        refresh()

        assertEquals(emptySet<String>(), hotRows())
    }

    @Test
    fun `a read without a stamp can't reset the replay check`() {
        feeds.hotList = HotFeedSnapshot(listOf(hot("+12125550101")), generatedAt = "2026-09-21T10:00:00+00:00")
        refresh()
        feeds.hotList = HotFeedSnapshot(listOf(hot("+12125550101")))
        refresh()

        feeds.hotList = HotFeedSnapshot(emptyList(), explicitlyCleared = true, generatedAt = "2026-09-05T12:00:00+00:00")
        refresh()

        assertEquals(setOf("+12125550101"), hotRows())
    }

    @Test
    fun `a feed stamped in the future doesn't lock out the genuine ones after it`() {
        feeds.hotList = HotFeedSnapshot(listOf(hot("+12125550101")), generatedAt = "2099-01-01T00:00:00+00:00")
        refresh()

        feeds.hotList =
            HotFeedSnapshot(
                listOf(hot("+12125550102")),
                generatedAt =
                    java.time.Instant
                        .now()
                        .plusSeconds(60)
                        .toString(),
            )
        refresh()

        assertEquals(setOf("+12125550102"), hotRows())
    }

    private fun hot(number: String) = HotNumber(number = number, type = "robocall", description = "")

    private fun refresh() =
        runBlocking {
            HotDataSync.refresh(context, feeds, fixture.repository, fixture.dao)
        }

    private fun hotRows(): Set<String> =
        runBlocking {
            fixture.dao
                .getNumbersBySource("hot_list")
                .map { it.number }
                .toSet()
        }

    private class ScriptedHotFeeds : HotFeedDataSource {
        var hotList = HotFeedSnapshot(emptyList<HotNumber>())
        private val quiet = HotFeedSnapshot(listOf("212555"), generatedAt = "2026-09-21T10:00:00+00:00")

        override suspend fun fetchHotList(
            owner: String,
            repo: String,
        ) = Result.success(hotList.data)

        override suspend fun fetchHotRanges(
            owner: String,
            repo: String,
        ) = Result.success(quiet.data)

        override suspend fun fetchSpamDomains(
            owner: String,
            repo: String,
        ) = Result.success(listOf("bad.example"))

        override suspend fun fetchHotListSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(hotList)

        override suspend fun fetchHotRangesSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(quiet)

        override suspend fun fetchSpamDomainsSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(HotFeedSnapshot(listOf("bad.example"), generatedAt = "2026-09-21T10:00:00+00:00"))

        override fun parseHotListJson(body: String): List<HotNumber> = emptyList()

        override fun parseHotRangesJson(body: String): List<String> = emptyList()

        override fun parseSpamDomainsJson(body: String): List<String> = emptyList()
    }
}
