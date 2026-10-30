package com.sysadmindoc.callshield.domain.model

import com.sysadmindoc.callshield.data.model.SpamNumber

/** How Rules > Blocked orders the numbers the user blocked. */
enum class BlocklistSort(
    val storedValue: String,
) {
    NEWEST("newest"),
    NUMBER("number"),
    MOST_BLOCKED("most_blocked"),
    ;

    /** [hitCounts] holds the blocked calls and texts logged per number; only [MOST_BLOCKED] reads it. */
    fun sort(
        numbers: List<SpamNumber>,
        hitCounts: Map<String, Int>,
    ): List<SpamNumber> =
        when (this) {
            // Blocks made before v21 have no time, so they go last, newest row first.
            NEWEST -> numbers.sortedWith(compareByDescending<SpamNumber> { it.blockedAt ?: Long.MIN_VALUE }.thenByDescending { it.id })

            NUMBER -> numbers.sortedBy { it.number }

            MOST_BLOCKED -> numbers.sortedWith(compareByDescending<SpamNumber> { hitCounts[it.number] ?: 0 }.thenBy { it.number })
        }

    companion object {
        /** A missing or unknown value keeps the number order the list always had. */
        fun fromStored(value: String?): BlocklistSort = entries.firstOrNull { it.storedValue == value } ?: NUMBER
    }
}
