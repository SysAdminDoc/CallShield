package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * Empty settings stores for one test. The app's own stores stay on the device
 * between runs, and state kept there, such as the feed version and date the
 * sync last accepted, makes a test depend on whatever ran before it.
 */
internal class TestSettingsStores(
    context: Context,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val directory = File(context.cacheDir, "test-settings-${UUID.randomUUID()}").apply { mkdirs() }

    val settings: DataStore<Preferences> = store("settings")
    val privateSettings: DataStore<Preferences> = store("private")

    private fun store(name: String) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptPreferences(),
            scope = scope,
            produceFile = { File(directory, "$name.preferences_pb") },
        )

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }
}
