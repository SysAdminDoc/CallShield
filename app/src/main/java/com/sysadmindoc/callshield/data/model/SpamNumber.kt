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
    /** JSON-encoded independent feed evidence; kept opaque on the hot path. */
    val evidenceJson: String = "[]",
    /** Earliest expiry among the retained feed-evidence records, if any. */
    val evidenceExpiresAt: Long? = null,
    val isUserBlocked: Boolean = false,
    val expiresAt: Long? = null,
) {
    fun activeDecision(now: Long = System.currentTimeMillis()): SpamNumber? {
        val evidenceActive = evidenceExpiresAt == null || evidenceExpiresAt > now
        if (!isUserBlocked) return takeIf { evidenceActive }
        if (expiresAt == null || expiresAt > now) return this
        if (source == "user" || !evidenceActive) return null
        return copy(isUserBlocked = false, expiresAt = null)
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
    val evidenceJson: String = "[]",
    val evidenceExpiresAt: Long? = null,
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
    val ruleId: Long? = null,
    val reasonCode: com.sysadmindoc.callshield.domain.model.BlockReasonCode =
        com.sysadmindoc.callshield.domain.model.BlockReasonCode
            .fromStored(matchReason),
    /** PASSporT origid only; raw identity tokens and URLs are never persisted. */
    val origid: String? = null,
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
    val ruleId: Long? = null,
    val reasonCode: com.sysadmindoc.callshield.domain.model.BlockReasonCode =
        com.sysadmindoc.callshield.domain.model.BlockReasonCode
            .fromStored(matchReason),
    /** PASSporT origid only; raw identity tokens and URLs are never persisted. */
    val origid: String? = null,
)

@JsonClass(generateAdapter = false)
data class SpamDatabase(
    val version: Int,
    val updated: String,
    val numbers: List<SpamNumberJson>,
    val prefixes: List<SpamPrefixJson>,
)

/** Metadata for the content-addressed database payloads published beside the legacy feed. */
@JsonClass(generateAdapter = false)
data class SpamShardManifest(
    @param:Json(name = "format_version") val formatVersion: Int,
    val version: Int,
    val updated: String,
    @param:Json(name = "legacy_path") val legacyPath: String,
    @param:Json(name = "shard_directory") val shardDirectory: String,
    @param:Json(name = "shard_count") val shardCount: Int,
    val shards: List<SpamShardDescriptor>,
)

@JsonClass(generateAdapter = false)
data class SpamShardDescriptor(
    val id: String,
    val path: String,
    val sha256: String,
    val bytes: Long,
    val numbers: Int,
    val prefixes: Int,
)

/** A single independently downloadable database payload. */
@JsonClass(generateAdapter = false)
data class SpamDatabaseShard(
    @param:Json(name = "shard_id") val shardId: String,
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
    val evidence: List<SourceEvidenceJson> = emptyList(),
)

/** Independent source evidence retained on an imported database row. */
@JsonClass(generateAdapter = false)
data class SourceEvidenceJson(
    @param:Json(name = "source_id") val sourceId: String,
    @param:Json(name = "evidence_type") val evidenceType: String,
    val license: String,
    val attribution: String,
    @param:Json(name = "first_seen") val firstSeen: String = "",
    @param:Json(name = "last_seen") val lastSeen: String = "",
    @param:Json(name = "retrieved_at") val retrievedAt: String = "",
    val geography: String = "global",
    @param:Json(name = "confidence_tier") val confidenceTier: String = "unverified",
    @param:Json(name = "parser_version") val parserVersion: String = "",
    @param:Json(name = "expires_at_epoch_ms") val expiresAtEpochMs: Long? = null,
    @param:Json(name = "complaint_role") val complaintRole: String? = null,
    @param:Json(name = "spoof_signal") val spoofSignal: String? = null,
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
    val evidence: List<SourceEvidenceJson> = emptyList(),
)
