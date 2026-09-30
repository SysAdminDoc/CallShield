package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Manual whitelist — numbers that should always be allowed through,
 * even if they match spam database or heuristic rules.
 * Separate from the contact-based whitelist.
 *
 * [isEmergency] entries are a curated subset the user wants to surface
 * prominently (kid's school, doctor, elder care). They appear in a
 * dedicated "Emergency" tab and get a distinct badge. Detection-wise
 * they match the same first short-circuit in [SpamRepository.isSpam]
 * as regular whitelist rows, but [matchSource] surfaces as
 * `emergency_contact` instead of `manual_whitelist` so the log + detail
 * panel can distinguish them. Schema added in DB v6.
 *
 * [rangeDigits] lets a permanent entry also allow every number that differs
 * from it only in its last 2 or 3 digits, for a practice or office that calls
 * from a block of lines. 0, the default, allows the exact number only.
 * Schema added in DB v19.
 */
@Entity(
    tableName = "whitelist",
    indices = [
        Index(value = ["number"], unique = true),
        Index(value = ["expiresAt"]),
    ],
)
data class WhitelistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val description: String = "",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val isEmergency: Boolean = false,
    val expiresAt: Long? = null,
    val rangeDigits: Int = 0,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt != null && expiresAt <= now

    /** Whether this entry may cover a block of numbers: permanent, and long enough that a block isn't a whole exchange. */
    val canCoverRange: Boolean
        get() = expiresAt == null && number.count { it in '0'..'9' } >= MIN_RANGE_NUMBER_DIGITS

    /** Whether [rangeDigits] is a size this entry can take; the exact number alone always is. */
    fun canCover(rangeDigits: Int): Boolean = rangeDigits == 0 || (rangeDigits in RANGE_DIGIT_OPTIONS && canCoverRange)

    companion object {
        /** The range sizes an entry can take; 0 is the exact number only. */
        val RANGE_DIGIT_OPTIONS = listOf(0, 2, 3)

        const val MIN_RANGE_NUMBER_DIGITS = 8
    }
}
