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
 */
@Entity(
    tableName = "whitelist",
    indices = [Index(value = ["number"], unique = true)]
)
data class WhitelistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val description: String = "",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val isEmergency: Boolean = false
)
