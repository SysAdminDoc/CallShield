package com.sysadmindoc.callshield.data

import java.util.concurrent.TimeUnit

/**
 * The reports this device made in the last 90 days and how each one fared,
 * kept as `id|number|type|reportedAt|delivery` strings for the My reports
 * list. The day's [CommunityReportLedger] decides what may be sent again;
 * this only remembers what was.
 */
internal object CommunityReportHistory {
    val WINDOW_MILLIS = TimeUnit.DAYS.toMillis(90)
    const val MAX_ENTRIES = 200
    private const val SEPARATOR = "|"
    private const val FIELDS = 5
    const val NOT_SPAM = "not_spam"

    enum class Delivery {
        /** The Worker has it. */
        SENT,

        /** Waiting in the outbox for a network or the end of a rate limit. */
        QUEUED,

        /** Refused, or given up on; nothing will send it. */
        NOT_SENT,
    }

    data class Entry(
        val id: String,
        val number: String,
        val type: String,
        val reportedAt: Long,
        val delivery: Delivery,
    )

    /**
     * [entries] with report [id]'s delivery set to [delivery]. A report
     * already listed keeps its date; a new one is dated [now]. Entries older
     * than the window are dropped, then the oldest past [MAX_ENTRIES].
     */
    fun record(
        entries: Set<String>,
        id: String,
        number: String,
        type: String,
        delivery: Delivery,
        now: Long,
    ): Set<String> {
        val kept = live(entries, now)
        val reportedAt = kept.firstOrNull { it.id == id }?.reportedAt ?: now
        return (kept.filterNot { it.id == id } + Entry(id, number, type, reportedAt, delivery))
            .sortedBy { it.reportedAt }
            .takeLast(MAX_ENTRIES)
            .mapTo(LinkedHashSet(), ::encode)
    }

    /**
     * Whether a not-spam report could still correct [entry]: it's a spam
     * report that went out or is on its way, and no not-spam report for the
     * number has been made since among [all].
     */
    fun canCorrect(
        entry: Entry,
        all: List<Entry>,
    ): Boolean =
        entry.type != NOT_SPAM &&
            entry.delivery != Delivery.NOT_SENT &&
            all.none { it.number == entry.number && it.type == NOT_SPAM && it.delivery != Delivery.NOT_SENT && it.reportedAt >= entry.reportedAt }

    /** The listed reports, newest first. */
    fun list(
        entries: Set<String>,
        now: Long,
    ): List<Entry> = live(entries, now).sortedByDescending { it.reportedAt }

    private fun live(
        entries: Set<String>,
        now: Long,
    ): List<Entry> = entries.mapNotNull(::decode).filter { now - it.reportedAt < WINDOW_MILLIS }

    internal fun encode(entry: Entry): String =
        listOf(entry.id, entry.number, entry.type, entry.reportedAt.toString(), entry.delivery.name).joinToString(SEPARATOR)

    internal fun decode(raw: String): Entry? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != FIELDS || parts.take(3).any(String::isEmpty)) return null
        val reportedAt = parts[3].toLongOrNull() ?: return null
        val delivery = Delivery.entries.firstOrNull { it.name == parts[4] } ?: return null
        return Entry(parts[0], parts[1], parts[2], reportedAt, delivery)
    }
}
