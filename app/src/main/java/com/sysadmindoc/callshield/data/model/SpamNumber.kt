package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@Entity(
    tableName = "spam_numbers",
    indices = [
        Index(value = ["number"], unique = true),
        Index(value = ["expiresAt"]),
    ],
)
data class SpamNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val type: String,
    val reports: Int = 1,
    @param:Json(name = "first_seen") val firstSeen: String = "",
    @param:Json(name = "last_seen") val lastSeen: String = "",
    val description: String = "",
    val source: String = "community",
    val isUserBlocked: Boolean = false,
    val expiresAt: Long? = null,
) {
    fun activeDecision(now: Long = System.currentTimeMillis()): SpamNumber? =
        when {
            expiresAt == null || expiresAt > now -> this
            !isUserBlocked -> this
            source == "user" -> null
            else -> copy(isUserBlocked = false, expiresAt = null)
        }
}

@Entity(
    tableName = "spam_prefixes",
    indices = [Index(value = ["prefix"], unique = true)],
)
data class SpamPrefix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prefix: String,
    val type: String,
    val description: String = "",
)

@Entity(
    tableName = "call_log",
    indices = [
        Index(value = ["number"]),
        Index(value = ["timestamp"]),
        Index(value = ["logKey"], unique = true),
    ],
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
    val confidence: Int = 100,
    val logKey: String? = null,
)

@Entity(
    tableName = "pending_blocked_call_logs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["nextAttemptAt"]),
    ],
)
data class PendingBlockedCallLog(
    @PrimaryKey val idempotencyKey: String,
    val number: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "unknown",
    val isCall: Boolean = true,
    val smsBody: String? = null,
    val matchReason: String = "",
    val confidence: Int = 100,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
)

@JsonClass(generateAdapter = false)
data class SpamDatabase(
    val version: Int,
    val updated: String,
    val numbers: List<SpamNumberJson>,
    val prefixes: List<SpamPrefixJson>,
)

@JsonClass(generateAdapter = false)
data class SpamNumberJson(
    val number: String,
    val type: String,
    val reports: Int = 1,
    @param:Json(name = "first_seen") val firstSeen: String = "",
    @param:Json(name = "last_seen") val lastSeen: String = "",
    val description: String = "",
)

data class NumberCount(
    val number: String,
    val cnt: Int,
)

/** A trending number from the 30-minute hot list sync. */
data class HotNumber(
    val number: String,
    val type: String = "robocall",
    val description: String = "Trending community report",
)

@JsonClass(generateAdapter = false)
data class SpamPrefixJson(
    val prefix: String,
    val type: String,
    val description: String = "",
)
