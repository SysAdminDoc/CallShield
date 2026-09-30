package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A phone number found in a text CallShield flagged, kept for 30 days so the
 * outgoing-call hold can stop a callback to it ("call 1-8xx to cancel").
 */
@Entity(tableName = "flagged_text_numbers")
data class FlaggedTextNumber(
    /** E.164, as [com.sysadmindoc.callshield.data.SmsContentAnalyzer.extractCallbackNumbers] writes it. */
    @PrimaryKey val number: String,
    /** When the latest flagged text with this number arrived. */
    val seenAt: Long,
)
