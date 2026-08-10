package com.sysadmindoc.callshield.data.repository

import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamShardDescriptor
import com.sysadmindoc.callshield.data.model.SpamShardManifest
import com.sysadmindoc.callshield.data.remote.sha256Hex
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal data class AcceptedSpamFeedMetadata(
    val version: Int?,
    val updated: String?,
    val manifestDigest: String?,
)

/** A remote feed was well-formed enough to fetch but would move local data backwards. */
internal class SpamFeedManifestRejectedException(
    message: String,
) : IllegalStateException(message)

private const val EXPECTED_FORMAT_VERSION = 1
private const val EXPECTED_DATABASE_PATH = "data/spam_numbers.json"
private const val EXPECTED_SHARD_DIRECTORY = "data/spam_number_shards"
private const val EXPECTED_SHARD_COUNT = 256
private const val MAX_SHARD_BYTES = 1L * 1024L * 1024L
private const val MAX_DATABASE_NUMBERS = 250_000L
private const val MAX_DATABASE_PREFIXES = 100_000L
private val shardIdPattern = Regex("[0-9a-f]{2}")
private val sha256Pattern = Regex("[0-9a-f]{64}")
private val isoDatePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

internal fun spamShardManifestDigest(manifest: SpamShardManifest): String {
    val canonical =
        buildString {
            append("format_version=").append(manifest.formatVersion).append('\n')
            append("version=").append(manifest.version).append('\n')
            append("updated=").append(manifest.updated).append('\n')
            append("legacy_path=").append(manifest.legacyPath).append('\n')
            append("shard_directory=").append(manifest.shardDirectory).append('\n')
            append("shard_count=").append(manifest.shardCount).append('\n')
            manifest.shards.sortedBy(SpamShardDescriptor::id).forEach { descriptor ->
                append("shard=")
                    .append(descriptor.id)
                    .append('|')
                    .append(descriptor.path)
                    .append('|')
                    .append(descriptor.sha256)
                    .append('|')
                    .append(descriptor.bytes)
                    .append('|')
                    .append(descriptor.numbers)
                    .append('|')
                    .append(descriptor.prefixes)
                    .append('\n')
            }
        }
    return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
}

internal fun validateSpamShardManifestPolicy(
    previous: AcceptedSpamFeedMetadata,
    manifest: SpamShardManifest,
    digest: String = spamShardManifestDigest(manifest),
) {
    validateManifestStructure(manifest)
    validateMonotonicMetadata(
        previous = previous,
        incomingVersion = manifest.version,
        incomingUpdated = manifest.updated,
        incomingDigest = digest,
        incomingKind = "shard manifest",
    )
}

internal fun validateSpamDatabasePolicy(
    previous: AcceptedSpamFeedMetadata,
    database: SpamDatabase,
) {
    requireValidUpdated(database.updated, "database")
    requirePolicy(database.version > 0, "database version must be positive")
    validateMonotonicMetadata(
        previous = previous,
        incomingVersion = database.version,
        incomingUpdated = database.updated,
        incomingDigest = null,
        incomingKind = "legacy database",
    )
}

private fun validateManifestStructure(manifest: SpamShardManifest) {
    requirePolicy(manifest.formatVersion == EXPECTED_FORMAT_VERSION) {
        "unsupported spam shard manifest format ${manifest.formatVersion}"
    }
    requirePolicy(manifest.version > 0) { "spam shard manifest version must be positive" }
    requireValidUpdated(manifest.updated, "spam shard manifest")
    requirePolicy(manifest.legacyPath == EXPECTED_DATABASE_PATH) {
        "spam shard manifest changed the legacy database path"
    }
    requirePolicy(manifest.shardDirectory == EXPECTED_SHARD_DIRECTORY) {
        "spam shard manifest has an invalid shard directory"
    }
    requirePolicy(manifest.shardCount == EXPECTED_SHARD_COUNT) {
        "spam shard manifest shard count ${manifest.shardCount} does not match $EXPECTED_SHARD_COUNT"
    }
    requirePolicy(manifest.shards.size <= EXPECTED_SHARD_COUNT) {
        "spam shard manifest has too many shard descriptors"
    }

    val ids = manifest.shards.map(SpamShardDescriptor::id)
    requirePolicy(ids.size == ids.toSet().size) { "spam shard manifest contains duplicate shard ids" }
    val totalNumbers = manifest.shards.sumOf { it.numbers.toLong() }
    val totalPrefixes = manifest.shards.sumOf { it.prefixes.toLong() }
    requirePolicy(totalNumbers <= MAX_DATABASE_NUMBERS) {
        "spam shard manifest number row count $totalNumbers exceeds cap $MAX_DATABASE_NUMBERS"
    }
    requirePolicy(totalPrefixes <= MAX_DATABASE_PREFIXES) {
        "spam shard manifest prefix row count $totalPrefixes exceeds cap $MAX_DATABASE_PREFIXES"
    }

    manifest.shards.forEach { descriptor ->
        requirePolicy(shardIdPattern.matches(descriptor.id)) {
            "spam shard id ${descriptor.id} is not two lowercase hexadecimal characters"
        }
        requirePolicy(descriptor.path == "$EXPECTED_SHARD_DIRECTORY/${descriptor.id}.json") {
            "spam shard ${descriptor.id} has an invalid path"
        }
        requirePolicy(sha256Pattern.matches(descriptor.sha256)) {
            "spam shard ${descriptor.id} has an invalid content hash"
        }
        requirePolicy(descriptor.bytes in 1..MAX_SHARD_BYTES) {
            "spam shard ${descriptor.id} has an invalid byte count"
        }
        requirePolicy(descriptor.numbers >= 0 && descriptor.prefixes >= 0) {
            "spam shard ${descriptor.id} has negative row counts"
        }
    }
}

private fun validateMonotonicMetadata(
    previous: AcceptedSpamFeedMetadata,
    incomingVersion: Int,
    incomingUpdated: String,
    incomingDigest: String?,
    incomingKind: String,
) {
    val previousVersion = previous.version ?: return
    val previousUpdated = previous.updated?.let { parseUpdated(it, "stored feed") }
    val incomingDate = parseUpdated(incomingUpdated, incomingKind)

    requirePolicy(incomingVersion >= previousVersion) {
        "$incomingKind version $incomingVersion is older than accepted version $previousVersion"
    }
    if (previousUpdated != null) {
        requirePolicy(!incomingDate.isBefore(previousUpdated)) {
            "$incomingKind updated date $incomingUpdated is older than accepted date ${previous.updated}"
        }
    }
    if (incomingVersion == previousVersion && previous.manifestDigest != null) {
        requirePolicy(incomingDigest == previous.manifestDigest) {
            "$incomingKind changed content without advancing its version"
        }
    }
}

private fun requireValidUpdated(
    updated: String,
    kind: String,
) {
    parseUpdated(updated, kind)
}

private fun parseUpdated(
    updated: String,
    kind: String,
): LocalDate {
    requirePolicy(isoDatePattern.matches(updated)) {
        "$kind updated date must use YYYY-MM-DD"
    }
    return try {
        LocalDate.parse(updated)
    } catch (_: DateTimeParseException) {
        throw SpamFeedManifestRejectedException("$kind updated date is invalid")
    }
}

private inline fun requirePolicy(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) {
        throw SpamFeedManifestRejectedException(message())
    }
}

private fun requirePolicy(
    condition: Boolean,
    message: String,
) {
    if (!condition) {
        throw SpamFeedManifestRejectedException(message)
    }
}
