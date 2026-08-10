package com.sysadmindoc.callshield.data.remote

import java.util.LinkedHashMap

/** Small, hash-keyed LRU cache that bounds live PhoneBlock request volume. */
internal class PhoneBlockLookupCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val fetchedAtMs: Long,
        val result: ExternalLookup.SourceResult,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(maxEntries.coerceAtLeast(1), 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry>?,
            ): Boolean = size > maxEntries
        }
    private val lock = Any()

    fun get(key: String): ExternalLookup.SourceResult? =
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized null
            if (clock() - entry.fetchedAtMs >= ttlMs) {
                entries.remove(key)
                null
            } else {
                entry.result
            }
        }

    fun put(
        key: String,
        result: ExternalLookup.SourceResult,
    ) {
        synchronized(lock) {
            entries[key] = Entry(clock(), result)
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    internal fun sizeForTests(): Int = synchronized(lock) { entries.size }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }
}
