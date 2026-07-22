package com.sysadmindoc.callshield.data

import android.os.SystemClock
import com.sysadmindoc.callshield.data.model.SpamNumber

data class OutgoingRiskWarning(
    val number: String,
    val reason: String,
    val confidence: Int,
)

/** Exact-match, local-only policy for optional outgoing call warnings. */
internal object OutgoingRiskPolicy {
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
    private const val WARNING_COOLDOWN_MS = 30_000L
    private const val MAX_RECENT_WARNINGS = 64
    private const val USER_BLOCK_CONFIDENCE = 100
    private const val HIGH_REPORT_CONFIDENCE = 90
    private const val MEDIUM_REPORT_CONFIDENCE = 75
    private const val BASE_CONFIDENCE = 65
    private const val HIGH_REPORT_COUNT = 10
    private const val MEDIUM_REPORT_COUNT = 3

    private val recentWarnings =
        object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>?): Boolean = size > MAX_RECENT_WARNINGS
        }

    suspend fun evaluate(
        repository: SpamRepository,
        rawNumber: String,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
    ): OutgoingRiskWarning? {
        val normalized = repository.normalizeNumber(rawNumber)
        val match = normalized.takeIf(String::isNotBlank)?.let { repository.findExactSpamNumber(it) }
        val warning = match?.toOutgoingWarning(normalized)
        return warning?.takeIf { shouldShow(normalized, nowElapsed) }
    }

    internal fun SpamNumber.toOutgoingWarning(normalized: String = number): OutgoingRiskWarning =
        OutgoingRiskWarning(
            number = normalized,
            reason = description.trim().ifBlank { type.replace('_', ' ') },
            confidence =
                when {
                    isUserBlocked -> USER_BLOCK_CONFIDENCE
                    reports >= HIGH_REPORT_COUNT -> HIGH_REPORT_CONFIDENCE
                    reports >= MEDIUM_REPORT_COUNT -> MEDIUM_REPORT_CONFIDENCE
                    else -> BASE_CONFIDENCE
                },
        )

    private fun shouldShow(
        normalized: String,
        nowElapsed: Long,
    ): Boolean =
        synchronized(recentWarnings) {
            val previous = recentWarnings[normalized]
            val shouldShow = previous == null || nowElapsed - previous >= WARNING_COOLDOWN_MS
            if (shouldShow) recentWarnings[normalized] = nowElapsed
            shouldShow
        }

    internal fun resetForTests() {
        synchronized(recentWarnings) { recentWarnings.clear() }
    }
}
