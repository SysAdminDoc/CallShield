package com.sysadmindoc.callshield.util

import java.util.concurrent.TimeUnit

/**
 * Pure staleness predicate over the last successful sync timestamp.
 *
 * The dashboard already *displays* "last synced …", but there is no proactive
 * warning when the local hot-campaign data silently decays to the bundled
 * snapshot — e.g. the device has been offline for days or the app landed in a
 * restricted App-Standby bucket and WorkManager sync stopped running.
 *
 * This is deliberately pure (no Android, no clock) so it is unit-testable and
 * the UI layer (owned by a separate surface) can simply read the result.
 */
object SyncFreshness {
    /** The periodic sync cadence, mirroring `SyncWorker` (6h). */
    val DEFAULT_SYNC_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

    /**
     * Number of sync intervals allowed to elapse before data is considered
     * stale. A few missed runs are normal (Doze batching, brief offline); 3×
     * the interval (~18h at the 6h cadence) is a conservative "something is
     * actually wrong" threshold.
     */
    const val DEFAULT_STALE_MULTIPLIER: Int = 3

    /**
     * True when the sync data should be treated as stale.
     *
     * @param lastSyncTimestamp epoch millis of the last successful sync; a
     *   non-positive value means "never synced", which is always stale.
     * @param now current epoch millis.
     * @param syncIntervalMillis the expected sync cadence.
     * @param staleMultiplier how many intervals may elapse before stale.
     */
    fun isStale(
        lastSyncTimestamp: Long,
        now: Long,
        syncIntervalMillis: Long = DEFAULT_SYNC_INTERVAL_MILLIS,
        staleMultiplier: Int = DEFAULT_STALE_MULTIPLIER,
    ): Boolean {
        if (lastSyncTimestamp <= 0L) return true
        val age = now - lastSyncTimestamp
        // A clock skewed backwards (age < 0) is not evidence of staleness.
        if (age < 0L) return false
        val threshold = syncIntervalMillis * staleMultiplier
        return age > threshold
    }

    /** Age of the last sync in millis, floored at 0; -1 when never synced. */
    fun ageMillis(
        lastSyncTimestamp: Long,
        now: Long,
    ): Long {
        if (lastSyncTimestamp <= 0L) return -1L
        return (now - lastSyncTimestamp).coerceAtLeast(0L)
    }
}
