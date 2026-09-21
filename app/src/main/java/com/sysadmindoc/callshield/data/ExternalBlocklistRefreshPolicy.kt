package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import java.util.concurrent.TimeUnit

/**
 * When a subscribed list is fetched again in the background, and when a fetch
 * is too suspicious to apply without the preview a person would have seen.
 */
internal object ExternalBlocklistRefreshPolicy {
    /** A list that declares no `Expires:` interval is refreshed daily. */
    val DEFAULT_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)

    /** Never sooner than this, whatever the list declares or however often it fails. */
    val MIN_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(6)

    /** A list that declares a longer interval is still checked weekly. */
    val MAX_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(7)

    fun intervalMillis(declaredHours: Int): Long =
        if (declaredHours <= 0) {
            DEFAULT_INTERVAL_MILLIS
        } else {
            TimeUnit.HOURS.toMillis(declaredHours.toLong()).coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
        }

    fun isDue(
        subscription: ExternalBlocklistSubscription,
        now: Long,
    ): Boolean {
        if (!subscription.enabled) return false
        // A stamp in the future means the clock went back. Waiting for it to
        // come round again would stall the list for as long as the jump.
        val sinceAttempt = now - subscription.lastAttemptAt
        if (subscription.lastAttemptAt > 0L && sinceAttempt in 0L until MIN_INTERVAL_MILLIS) return false
        val sinceSuccess = now - subscription.lastSyncedAt
        return subscription.lastSyncedAt <= 0L ||
            sinceSuccess < 0L ||
            sinceSuccess >= intervalMillis(subscription.declaredRefreshHours)
    }

    /**
     * A download holding under half of what the list holds now is kept back,
     * as an empty one is. A publisher's broken export looks exactly like a
     * list that cleaned itself up, and only a person looking at the preview
     * can tell them apart.
     */
    fun isSuspiciousShrink(
        currentRows: Int,
        nextRows: Int,
    ): Boolean = currentRows > 0 && nextRows.toLong() * 2 < currentRows
}
