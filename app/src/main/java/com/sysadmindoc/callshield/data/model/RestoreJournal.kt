package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable bridge between the DataStore and Room halves of a backup restore. */
@Entity(tableName = "restore_journal")
data class RestoreJournal(
    @PrimaryKey
    val journalId: Int = SINGLETON_ID,
    val phase: String,
    val beforeSettingsJson: String,
    val desiredSettingsJson: String,
    val createdAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val PHASE_PREPARED = "prepared"
        const val PHASE_ROOM_COMMITTED = "room_committed"
    }
}
