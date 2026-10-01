package com.sysadmindoc.callshield.data.model

import androidx.room.Entity

/**
 * A phone number found in a text CallShield flagged, kept for 30 days so the
 * outgoing-call hold can stop a callback to it ("call 1-8xx to cancel").
 */
@Entity(tableName = "flagged_text_numbers", primaryKeys = ["number", "sender"])
data class FlaggedTextNumber(
    /** E.164, as [com.sysadmindoc.callshield.data.SmsContentAnalyzer.extractCallbackNumbers] writes it. */
    val number: String,
    /** Who sent the text, as the blocked log names them, so "Not spam" on them forgets it. */
    val sender: String,
    /** When the latest flagged text from [sender] with this number arrived. */
    val seenAt: Long,
)
