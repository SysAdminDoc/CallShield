package com.sysadmindoc.callshield.service

/**
 * Process-session notification gate for safety explanations that should not
 * repeat every time the same caller triggers the same allow rule.
 */
internal class OneShotNoticeGate(
    private val retentionMillis: Long = 6 * 60 * 60 * 1_000L,
) {
    private val shownAt = linkedMapOf<String, Long>()

    @Synchronized
    fun shouldShow(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        prune(nowMillis)
        if (shownAt.containsKey(key)) return false
        shownAt[key] = nowMillis
        return true
    }

    @Synchronized
    fun clear() {
        shownAt.clear()
    }

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - retentionMillis
        val iterator = shownAt.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
    }
}
