package com.sysadmindoc.callshield.data

/**
 * Graph-based campaign detection via NPA-NXX prefix clustering.
 *
 * Tracks the 6-digit NPA-NXX prefix of every incoming call in memory.
 * When [BURST_THRESHOLD] or more calls arrive from the same prefix
 * within [WINDOW_MS], the prefix is flagged as an active spam campaign.
 *
 * Thread-safe: all access is synchronized.
 */
object CampaignDetector {
    private val lock = Any()
    private val recentPrefixes = mutableMapOf<String, MutableList<Long>>()
    private const val WINDOW_MS = 3_600_000L // 1 hour window
    private const val BURST_THRESHOLD = 5    // 5+ calls from same prefix = campaign
    private const val MAX_TRACKED_PREFIXES = 1000

    fun recordCall(number: String) {
        val prefix = extractNpaNxx(number) ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneExpiredEntries(now)
            val timestamps = recentPrefixes.getOrPut(prefix) { mutableListOf() }
            timestamps.add(now)
            trimTrackedPrefixes()
        }
    }

    fun isActiveCampaign(number: String): Boolean {
        val prefix = extractNpaNxx(number) ?: return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneExpiredEntries(now)
            val timestamps = recentPrefixes[prefix] ?: return false
            return timestamps.size >= BURST_THRESHOLD
        }
    }

    fun getActiveCampaigns(): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneExpiredEntries(now)
            return recentPrefixes.entries
                .map { (prefix, times) -> prefix to times.size }
                .filter { it.second >= BURST_THRESHOLD }
                .sortedByDescending { it.second }
        }
    }

    private fun pruneExpiredEntries(now: Long) {
        recentPrefixes.entries.removeAll { (_, timestamps) ->
            timestamps.removeAll { now - it > WINDOW_MS }
            timestamps.isEmpty()
        }
    }

    private fun trimTrackedPrefixes() {
        if (recentPrefixes.size <= MAX_TRACKED_PREFIXES) return

        // Evict the stalest entries. Finding the min-max is O(n) per eviction,
        // which beats sorting O(n log n) since overflow is typically small (1-2).
        while (recentPrefixes.size > MAX_TRACKED_PREFIXES) {
            val stalest = recentPrefixes.entries.minByOrNull { (_, timestamps) ->
                timestamps.maxOrNull() ?: Long.MIN_VALUE
            }?.key ?: break
            recentPrefixes.remove(stalest)
        }
    }

    private fun extractNpaNxx(number: String): String? {
        val digits = number.filter { it.isDigit() }
        val normalized = when {
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length == 10 -> digits
            else -> return null
        }
        return normalized.substring(0, 6) // NPA-NXX
    }
}
