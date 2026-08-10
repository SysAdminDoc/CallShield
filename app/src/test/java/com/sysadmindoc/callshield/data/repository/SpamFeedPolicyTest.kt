package com.sysadmindoc.callshield.data.repository

import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamShardDescriptor
import com.sysadmindoc.callshield.data.model.SpamShardManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpamFeedPolicyTest {
    @Test
    fun `newer manifest is accepted and fingerprint is stable`() {
        val manifest = manifest(version = 2, updated = "2026-08-10")
        val previous = AcceptedSpamFeedMetadata(version = 1, updated = "2026-08-09", manifestDigest = "old")

        validateSpamShardManifestPolicy(previous, manifest)
        assertEquals(spamShardManifestDigest(manifest), spamShardManifestDigest(manifest.copy(shards = manifest.shards.reversed())))
    }

    @Test
    fun `replayed or downgraded manifest is rejected`() {
        val previousManifest = manifest(version = 2, updated = "2026-08-10")
        val previous =
            AcceptedSpamFeedMetadata(
                version = previousManifest.version,
                updated = previousManifest.updated,
                manifestDigest = spamShardManifestDigest(previousManifest),
            )

        assertThrows(SpamFeedManifestRejectedException::class.java) {
            validateSpamShardManifestPolicy(previous, manifest(version = 1, updated = "2026-08-11"))
        }
        assertThrows(SpamFeedManifestRejectedException::class.java) {
            validateSpamShardManifestPolicy(previous, manifest(version = 3, updated = "2026-08-09"))
        }
    }

    @Test
    fun `same version content mutation is rejected`() {
        val accepted = manifest(version = 2, updated = "2026-08-10")
        val previous =
            AcceptedSpamFeedMetadata(
                version = accepted.version,
                updated = accepted.updated,
                manifestDigest = spamShardManifestDigest(accepted),
            )
        val mutated = accepted.copy(shards = listOf(accepted.shards.single().copy(sha256 = "1".repeat(64))))

        assertNotEquals(spamShardManifestDigest(accepted), spamShardManifestDigest(mutated))
        assertThrows(SpamFeedManifestRejectedException::class.java) {
            validateSpamShardManifestPolicy(previous, mutated)
        }
    }

    @Test
    fun `malformed manifest metadata is rejected before comparison`() {
        val malformed =
            manifest(version = 2, updated = "2026-08-10").copy(
                shards =
                    listOf(
                        manifest(version = 2, updated = "2026-08-10")
                            .shards
                            .single()
                            .copy(path = "data/escape.json"),
                    ),
            )

        assertThrows(SpamFeedManifestRejectedException::class.java) {
            validateSpamShardManifestPolicy(AcceptedSpamFeedMetadata(null, null, null), malformed)
        }
    }

    @Test
    fun `legacy database cannot replace an accepted sharded version`() {
        val accepted = manifest(version = 2, updated = "2026-08-10")
        val previous =
            AcceptedSpamFeedMetadata(
                version = accepted.version,
                updated = accepted.updated,
                manifestDigest = spamShardManifestDigest(accepted),
            )
        val database =
            SpamDatabase(
                version = 2,
                updated = "2026-08-10",
                numbers = emptyList(),
                prefixes = emptyList(),
            )

        assertThrows(SpamFeedManifestRejectedException::class.java) {
            validateSpamDatabasePolicy(previous, database)
        }
    }

    private fun manifest(
        version: Int,
        updated: String,
    ): SpamShardManifest =
        SpamShardManifest(
            formatVersion = 1,
            version = version,
            updated = updated,
            legacyPath = "data/spam_numbers.json",
            shardDirectory = "data/spam_number_shards",
            shardCount = 256,
            shards =
                listOf(
                    SpamShardDescriptor(
                        id = "ab",
                        path = "data/spam_number_shards/ab.json",
                        sha256 = "0".repeat(64),
                        bytes = 1,
                        numbers = 0,
                        prefixes = 0,
                    ),
                ),
        )
}
