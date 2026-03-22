package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Manual whitelist — numbers that should always be allowed through,
 * even if they match spam database or heuristic rules.
 * Separate from the contact-based whitelist.
 */
@Entity(
    tableName = "whitelist",
    indices = [Index(value = ["number"], unique = true)]
)
data class WhitelistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val description: String = "",
    val addedTimestamp: Long = System.currentTimeMillis()
)
