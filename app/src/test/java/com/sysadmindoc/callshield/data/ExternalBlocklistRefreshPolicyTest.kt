package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.ExternalBlocklistRefreshPolicy.intervalMillis
import com.sysadmindoc.callshield.data.ExternalBlocklistRefreshPolicy.isDue
import com.sysadmindoc.callshield.data.ExternalBlocklistRefreshPolicy.isSuspiciousShrink
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExternalBlocklistRefreshPolicyTest {
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val now = 1_790_000_000_000L

    private fun subscription(
        lastSyncedAt: Long,
        lastAttemptAt: Long = lastSyncedAt,
        declaredRefreshHours: Int = 0,
        enabled: Boolean = true,
    ) = ExternalBlocklistSubscription(
        id = "a",
        label = "A",
        url = "https://lists.example.test/a.txt",
        enabled = enabled,
        lastSyncedAt = lastSyncedAt,
        declaredRefreshHours = declaredRefreshHours,
        lastAttemptAt = lastAttemptAt,
    )

    @Test
    fun `a list that declares nothing is refreshed daily`() {
        assertFalse(isDue(subscription(now - 23 * hour), now))
        assertTrue(isDue(subscription(now - 24 * hour), now))
    }

    @Test
    fun `a declared interval is honoured between six hours and a week`() {
        assertEquals(6 * hour, intervalMillis(1))
        assertEquals(12 * hour, intervalMillis(12))
        assertEquals(7 * 24 * hour, intervalMillis(90 * 24))
        assertFalse(isDue(subscription(now - 5 * hour, declaredRefreshHours = 1), now))
        assertTrue(isDue(subscription(now - 6 * hour, declaredRefreshHours = 1), now))
    }

    @Test
    fun `a failing list is retried no more than every six hours`() {
        val lastGood = now - 30 * hour
        assertFalse(isDue(subscription(lastGood, lastAttemptAt = now - 2 * hour), now))
        assertTrue(isDue(subscription(lastGood, lastAttemptAt = now - 6 * hour), now))
    }

    @Test
    fun `a list never synced is due, and a disabled one never is`() {
        assertTrue(isDue(subscription(lastSyncedAt = 0L, lastAttemptAt = 0L), now))
        assertFalse(isDue(subscription(now - 90 * hour, enabled = false), now))
    }

    @Test
    fun `a clock set backwards does not stall a list`() {
        assertTrue(isDue(subscription(now + 48 * hour), now))
    }

    @Test
    fun `losing more than half the rows is suspicious and losing half is not`() {
        assertTrue(isSuspiciousShrink(currentRows = 100, nextRows = 49))
        assertFalse(isSuspiciousShrink(currentRows = 100, nextRows = 50))
        assertTrue(isSuspiciousShrink(currentRows = 3, nextRows = 1))
        assertFalse(isSuspiciousShrink(currentRows = 0, nextRows = 0))
        assertFalse(isSuspiciousShrink(currentRows = 10, nextRows = 40))
    }
}
