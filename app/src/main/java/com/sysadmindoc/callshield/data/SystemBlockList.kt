package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.BlockedNumberContract

/**
 * Read-only adapter over Android's system-wide [BlockedNumberContract.BlockedNumbers].
 *
 * This is the table Google Phone / Messages / stock dialers read to
 * suppress unwanted callers at the telecom layer. Mirroring into it is
 * gated behind the default-dialer role; reading from it also requires
 * default-dialer on API 30+ (or `READ_BLOCKED_NUMBERS`, a system
 * permission). For non-privileged apps the query throws
 * [SecurityException] — we catch and return "unknown", never crash.
 *
 * **Why read-only**: adding write support is a larger UX change (we'd
 * have to guide the user through becoming default dialer and manage the
 * blocklist bidirectionally). Reading is the cheap win — if the user has
 * already granted CallShield the role (or uses a ROM where this query is
 * permitted), we get free continuity with their stock-dialer block list.
 *
 * Reference: Fossify Phone's `Context.isNumberBlocked` extension.
 */
object SystemBlockList {

    // Short TTL cache for the hot path — querying a content provider
    // takes a few ms and we may look up the same number 2-3 times per
    // screening call (checker chain + UI lookup).
    private const val CACHE_TTL_MS = 30_000L
    private const val CACHE_MAX = 128

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, Pair<Long, Boolean>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<Long, Boolean>>?): Boolean =
            size > CACHE_MAX
    }

    /**
     * Availability signal — `false` when we know reads will throw on this
     * device (not the default dialer, old API, unsupported ROM). Cached
     * negatively so the checker returns early without the exception cost.
     *
     * Re-checked every [AVAILABILITY_RECHECK_MS] in case the user grants
     * or revokes the default-dialer role while the process is alive.
     */
    @Volatile
    private var availableAt: Long = 0L

    @Volatile
    private var available: Boolean = false

    private const val AVAILABILITY_RECHECK_MS = 5L * 60_000L

    private fun checkAvailability(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - availableAt < AVAILABILITY_RECHECK_MS) return available
        available = try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (_: Throwable) {
            false
        }
        availableAt = now
        return available
    }

    /**
     * @return `true` if the system-wide block list contains [number];
     *         `false` otherwise, including when the app lacks permission
     *         to read the table.
     */
    fun isBlocked(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (!checkAvailability(context)) return false

        synchronized(cacheLock) {
            val cached = cache[number]
            if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val found = try {
            BlockedNumberContract.isBlocked(context, number)
        } catch (_: SecurityException) {
            // Role was revoked between availability check and this call —
            // clear the availability so we don't keep retrying, and wipe
            // the lookup cache so stale `true` entries from the previous
            // dialer-role session can't influence subsequent checks.
            available = false
            availableAt = System.currentTimeMillis()
            synchronized(cacheLock) { cache.clear() }
            false
        } catch (_: Throwable) {
            false
        }

        synchronized(cacheLock) {
            cache[number] = System.currentTimeMillis() to found
        }
        return found
    }

    /** Invalidate the lookup cache — used after an external block-list change. */
    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
        availableAt = 0L
    }
}
