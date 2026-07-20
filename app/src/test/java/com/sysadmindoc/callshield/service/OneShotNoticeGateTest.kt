package com.sysadmindoc.callshield.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotNoticeGateTest {
    @Test
    fun `shouldShow allows first notice and suppresses duplicate`() {
        val gate = OneShotNoticeGate(retentionMillis = 1_000L)

        assertTrue(gate.shouldShow("caller", nowMillis = 1_000L))
        assertFalse(gate.shouldShow("caller", nowMillis = 1_100L))
    }

    @Test
    fun `shouldShow allows notice again after retention window`() {
        val gate = OneShotNoticeGate(retentionMillis = 1_000L)

        assertTrue(gate.shouldShow("caller", nowMillis = 1_000L))
        assertTrue(gate.shouldShow("caller", nowMillis = 2_001L))
    }

    @Test
    fun `unbounded unique keys do not exceed maxEntries cap`() {
        // Cap below the retention window so the LRU eviction (not TTL prune)
        // is what trims the map. Without the cap, this loop used to grow the
        // map linearly until OOM on a long-lived process that sees many
        // unique callers.
        val gate = OneShotNoticeGate(retentionMillis = 60_000L, maxEntries = 16)
        for (i in 0 until 1_000) {
            assertTrue(gate.shouldShow("caller-$i", nowMillis = 1_000L + i))
        }
        assertTrue(
            "map should be bounded by maxEntries — was ${gate.size()}",
            gate.size() <= 16,
        )
        // Oldest entry should have been evicted; newest survives.
        assertFalse(gate.shouldShow("caller-999", nowMillis = 1_999L))
        assertTrue(gate.shouldShow("caller-0", nowMillis = 2_000L))
    }
}
