package com.sysadmindoc.callshield.data

import java.util.concurrent.TimeUnit

/**
 * The reports this device sent or queued in the last day, one entry per
 * number and vote, kept as `number|vote|epochMillis` strings.
 *
 * Without it the same report went out again whenever someone tapped report
 * twice or retried after a slow response: issue #10's report arrived three
 * times minutes apart, and one number arrived 12 times on 2026-08-31. The
 * Worker's own dedup lasts five minutes and keys on the network address, so it
 * catches neither.
 */
internal object CommunityReportLedger {
    val WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24)
    const val MAX_ENTRIES = 256
    private const val SEPARATOR = "|"

    /**
     * The vote a report type casts. The ledger keys on this, not on the type:
     * a notification's Block sends "spam" and Number Detail sends the row's
     * category ("robocall"), and both are the same vote from the same person.
     */
    fun voteOf(type: String): String =
        when (type) {
            "not_spam" -> "not_spam"
            "sms_spam" -> "sms_spam"
            else -> "spam"
        }

    /**
     * Entries still inside the window. A stamp from the future is dropped: it
     * was written under a wrong clock, and keeping it would block that report
     * for as long as the clock was off.
     */
    fun prune(
        entries: Set<String>,
        now: Long,
    ): Set<String> =
        entries.filterTo(LinkedHashSet()) { entry ->
            val stamp = entry.substringAfterLast(SEPARATOR).toLongOrNull() ?: return@filterTo false
            now - stamp in 0L until WINDOW_MILLIS
        }

    fun contains(
        entries: Set<String>,
        number: String,
        vote: String,
    ): Boolean = entries.any { it.startsWith(keyOf(number, vote) + SEPARATOR) }

    /** Adds the report, dropping the oldest entries past [MAX_ENTRIES]. */
    fun add(
        entries: Set<String>,
        number: String,
        vote: String,
        now: Long,
    ): Set<String> {
        val next = entries + "${keyOf(number, vote)}$SEPARATOR$now"
        if (next.size <= MAX_ENTRIES) return next
        return next
            .sortedBy { it.substringAfterLast(SEPARATOR).toLongOrNull() ?: Long.MIN_VALUE }
            .takeLast(MAX_ENTRIES)
            .toSet()
    }

    /** Drops the claim on a report that was refused or given up on, so it can be made again. */
    fun remove(
        entries: Set<String>,
        number: String,
        vote: String,
    ): Set<String> = entries.filterNotTo(LinkedHashSet()) { it.startsWith(keyOf(number, vote) + SEPARATOR) }

    private fun keyOf(
        number: String,
        vote: String,
    ) = "$number$SEPARATOR$vote"
}
