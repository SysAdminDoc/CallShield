package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@Entity(
    tableName = "spam_numbers",
    indices = [Index(value = ["number"], unique = true)]
)
data class SpamNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val type: String,
    val reports: Int = 1,
    @Json(name = "first_seen") val firstSeen: String = "",
    @Json(name = "last_seen") val lastSeen: String = "",
    val description: String = "",
    val source: String = "community",
    val isUserBlocked: Boolean = false
)

@Entity(
    tableName = "spam_prefixes",
    indices = [Index(value = ["prefix"], unique = true)]
)
data class SpamPrefix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prefix: String,
    val type: String,
    val description: String = ""
)

@Entity(
    tableName = "call_log",
    indices = [Index(value = ["number"]), Index(value = ["timestamp"])]
)
data class BlockedCall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "unknown",
    val wasBlocked: Boolean = true,
    val isCall: Boolean = true,
    val smsBody: String? = null,
    val matchReason: String = "",
    val confidence: Int = 100
)

@JsonClass(generateAdapter = false)
data class SpamDatabase(
    val version: Int,
    val updated: String,
    val numbers: List<SpamNumberJson>,
    val prefixes: List<SpamPrefixJson>
)

@JsonClass(generateAdapter = false)
data class SpamNumberJson(
    val number: String,
    val type: String,
    val reports: Int = 1,
    @Json(name = "first_seen") val firstSeen: String = "",
    @Json(name = "last_seen") val lastSeen: String = "",
    val description: String = ""
)

@JsonClass(generateAdapter = false)
data class SpamPrefixJson(
    val prefix: String,
    val type: String,
    val description: String = ""
)
