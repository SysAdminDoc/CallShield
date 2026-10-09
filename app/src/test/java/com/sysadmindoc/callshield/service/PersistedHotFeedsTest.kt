package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.data.remote.CommunityWatchNumber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** The kept copy of a feed comes back as it went in, and a missing or odd one counts as none. */
class PersistedHotFeedsTest {
    private val directory = Files.createTempDirectory("hot-feeds-test").toFile()
    private val kept = PersistedHotFeeds(directory)

    @After
    fun tearDown() {
        kept.clear()
    }

    @Test
    fun `a feed reads back as written, and a cleared one as empty`() {
        kept.write(HotDataSync.HOT_RANGES_FEED, listOf("212555", "303555"))
        kept.write(HotDataSync.SPAM_DOMAINS_FEED, emptyList())

        assertEquals(listOf("212555", "303555"), kept.read(HotDataSync.HOT_RANGES_FEED))
        assertEquals(emptyList<String>(), kept.read(HotDataSync.SPAM_DOMAINS_FEED))
        assertNull(kept.read(HotDataSync.COMMUNITY_WATCH_FEED))
    }

    @Test
    fun `a later write replaces the whole copy`() {
        kept.write(HotDataSync.HOT_RANGES_FEED, listOf("212555"))
        kept.write(HotDataSync.HOT_RANGES_FEED, listOf("303555"))

        assertEquals(listOf("303555"), kept.read(HotDataSync.HOT_RANGES_FEED))
    }

    @Test
    fun `watch entries keep their reporter counts, and a damaged line is dropped`() {
        val entries = listOf(CommunityWatchNumber("+33412345678", 2), CommunityWatchNumber("+12125550101", 7))

        val lines = HotDataSync.encodeCommunityWatch(entries)

        assertEquals(entries, HotDataSync.decodeCommunityWatch(lines))
        assertEquals(entries, HotDataSync.decodeCommunityWatch(lines + "+15550100" + "+15550101\tmany"))
    }

    @Test
    fun `clearing forgets every copy`() {
        kept.write(HotDataSync.HOT_RANGES_FEED, listOf("212555"))

        kept.clear()

        assertNull(kept.read(HotDataSync.HOT_RANGES_FEED))
    }
}
