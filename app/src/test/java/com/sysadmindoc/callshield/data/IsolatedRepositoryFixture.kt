package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.OkHttpExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Owns every persistent resource used by one Robolectric test sandbox. */
internal class IsolatedRepositoryFixture(
    context: Context,
    externalBlocklistDataSource: ExternalBlocklistDataSource = OkHttpExternalBlocklistDataSource(),
    remote: SpamDataSource = GitHubDataSource(),
) : AutoCloseable {
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
    private val storeDirectory = Files.createTempDirectory("callshield-test-datastore-").toFile()
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val dao = database.spamDao()

    val repository =
        SpamRepository(
            context = context,
            database = database,
            settingsDataStore = preferenceStore("settings"),
            privateSettingsDataStore = preferenceStore("private"),
            externalBlocklistDataSource = externalBlocklistDataSource,
            remote = remote,
        )

    private fun preferenceStore(name: String) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptPreferences(),
            scope = storeScope,
            produceFile = { File(storeDirectory, "$name.preferences_pb") },
        )

    /** Closes the database under the repository, so its next write throws. */
    fun breakDatabase() = database.close()

    override fun close() {
        database.close()
        runBlocking {
            storeJob.cancelAndJoin()
        }
        check(storeDirectory.deleteRecursively()) {
            "Could not remove isolated repository fixture at ${storeDirectory.absolutePath}"
        }
    }
}
