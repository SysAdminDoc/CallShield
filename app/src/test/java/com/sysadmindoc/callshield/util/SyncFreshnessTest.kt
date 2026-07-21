package com.sysadmindoc.callshield.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Unit tests for the pure [SyncFreshness] staleness predicate. */
class SyncFreshnessTest {
    private val now = 1_000_000_000_000L
    private val interval = TimeUnit.HOURS.toMillis(6)

    @Test
    fun `never synced is stale`() {
        assertTrue(SyncFreshness.isStale(0L, now))
        assertTrue(SyncFreshness.isStale(-5L, now))
    }

    @Test
    fun `just synced is fresh`() {
        assertFalse(SyncFreshness.isStale(now, now))
    }

    @Test
    fun `within threshold is fresh`() {
        val lastSync = now - (interval * 3) + 1 // just under 3 intervals
        assertFalse(SyncFreshness.isStale(lastSync, now))
    }

    @Test
    fun `just past threshold is stale`() {
        val lastSync = now - (interval * 3) - 1 // just over 3 intervals
        assertTrue(SyncFreshness.isStale(lastSync, now))
    }

    @Test
    fun `backward clock skew is not stale`() {
        // last sync in the "future" relative to now — do not flag as stale.
        assertFalse(SyncFreshness.isStale(now + interval, now))
    }

    @Test
    fun `custom multiplier tightens the window`() {
        val lastSync = now - (interval + 1)
        assertTrue(SyncFreshness.isStale(lastSync, now, staleMultiplier = 1))
        assertFalse(SyncFreshness.isStale(lastSync, now, staleMultiplier = 3))
    }

    @Test
    fun `ageMillis reports never and floors negatives`() {
        assertEquals(-1L, SyncFreshness.ageMillis(0L, now))
        assertEquals(0L, SyncFreshness.ageMillis(now + 5, now))
        assertEquals(interval, SyncFreshness.ageMillis(now - interval, now))
    }
}
