package com.sysadmindoc.callshield.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SettingsRepositoryFeedMetadataTest {
    @Test
    fun `sync success persists feed ordering metadata`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStoreFile = File.createTempFile("callshield-feed-metadata-", ".preferences_pb")
            dataStoreFile.delete()
            try {
                val dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = scope,
                        produceFile = { dataStoreFile },
                    )
                val settings = SettingsRepository(dataStore, dataStore)
                val digest = "a".repeat(64)

                settings.recordSyncSuccess(
                    sha = "commit-sha",
                    syncSource = SpamRepository.SYNC_SOURCE_REMOTE,
                    databaseVersion = 42,
                    shardHashes = mapOf("ab" to "b".repeat(64)),
                    databaseUpdated = "2026-08-10",
                    manifestDigest = digest,
                )

                assertEquals(
                    AcceptedSpamFeedMetadata(42, "2026-08-10", digest),
                    settings.readAcceptedSpamFeedMetadata(),
                )
            } finally {
                scope.cancel()
                dataStoreFile.delete()
            }
        }
}
