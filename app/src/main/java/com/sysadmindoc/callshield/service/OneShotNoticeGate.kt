package com.sysadmindoc.callshield.service

/**
 * Process-session notification gate for safety explanations that should not
 * repeat every time the same caller triggers the same allow rule.
 *
 * Bounded in two dimensions:
 *  - Time: entries older than `retentionMillis` are pruned opportunistically.
 *  - Count: a hard cap of `maxEntries` (LRU-evicted via the LinkedHashMap
 *    insertion order). Without this cap, a long-running process that
 *    receives unique caller IDs would grow the map without bound — on
 *    devices left unrestarted for weeks this can be tens of thousands of
 *    entries × hundreds of bytes each. Cap kicks in well below the TTL.
 */
internal class OneShotNoticeGate(
    private val retentionMillis: Long = 6 * 60 * 60 * 1_000L,
    private val maxEntries: Int = 1024,
) {
    private val shownAt = linkedMapOf<String, Long>()

    @Synchronized
    fun shouldShow(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        prune(nowMillis)
        if (shownAt.containsKey(key)) return false
        shownAt[key] = nowMillis
        enforceCap()
        return true
    }

    @Synchronized
    fun clear() {
        shownAt.clear()
    }

    /** Test/debug visibility — current entry count after any pruning. */
    @Synchronized
    internal fun size(): Int = shownAt.size

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - retentionMillis
        val iterator = shownAt.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
    }

    /**
     * LRU eviction: LinkedHashMap preserves insertion order, so dropping
     * `entries.iterator().next()` removes the oldest entry. Called only on
     * insert paths; reads never mutate.
     */
    private fun enforceCap() {
        while (shownAt.size > maxEntries) {
            val first = shownAt.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            } else break
        }
    }
}
