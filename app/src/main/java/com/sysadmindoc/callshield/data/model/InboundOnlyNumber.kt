package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A number the user says never calls them, like the line on the back of a
 * bank card that the bank only takes calls on. A call that shows it is
 * spoofed, so screening turns it away ahead of contacts and allows (the
 * bank's number is often a saved contact). Calling it out is untouched.
 * [number] is canonical. Schema added in DB v22.
 */
@Entity(tableName = "inbound_only_numbers")
data class InboundOnlyNumber(
    @PrimaryKey val number: String,
    val description: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Short codes and service numbers aren't a company's published line. */
        const val MIN_DIGITS = 7

        fun canMark(number: String): Boolean = number.count { it in '0'..'9' } >= MIN_DIGITS
    }
}
