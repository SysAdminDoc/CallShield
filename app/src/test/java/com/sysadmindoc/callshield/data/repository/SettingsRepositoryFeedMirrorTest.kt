package com.sysadmindoc.callshield.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The feed mirror setting stores only addresses the download path can use.
 * Robolectric supplies a real SDK level: on a bare JVM DataStore falls back to
 * File.renameTo, which can't replace a file on Windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsRepositoryFeedMirrorTest {
    private val directory: File = Files.createTempDirectory("callshield-feed-mirror-").toFile()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val settings =
        SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") },
            privateDataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "private.preferences_pb") },
        )

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test
    fun `a usable address is stored in its normal form, and removing it clears it`() =
        runBlocking {
            assertTrue(settings.setFeedMirrorUrl("https://mirror.example.test/callshield"))
            assertEquals("https://mirror.example.test/callshield/", settings.feedMirrorUrl.first())

            assertTrue(settings.setFeedMirrorUrl(null))
            assertNull(settings.feedMirrorUrl.first())
        }

    @Test
    fun `an address the download path can't use is refused and the saved one stays`() =
        runBlocking {
            settings.setFeedMirrorUrl("https://mirror.example.test/")

            assertFalse(settings.setFeedMirrorUrl("http://mirror.example.test/"))
            assertEquals("https://mirror.example.test/", settings.feedMirrorUrl.first())
        }
}
