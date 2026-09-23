package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RestoreSentinelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private val sentinel get() = File(context.noBackupFilesDir, "restore-journal-clean")

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
        sentinel.deleteRecursively()
    }

    @Test
    fun `without proof of a clean state startup looks in Room`() {
        assertFalse(RestoreSentinel.isClean(context))

        RestoreSentinel.markClean(context)
        assertTrue(RestoreSentinel.isClean(context))

        RestoreSentinel.markDirty(context)
        assertFalse(RestoreSentinel.isClean(context))
    }

    @Test
    fun `a settings restore withdraws the proof before its journal exists`() =
        runBlocking {
            RestoreSentinel.markClean(context)

            val result =
                BackupRestore.restorePayload(
                    context = context,
                    payload = settingsPayload(),
                    mode = BackupRestore.RestoreMode.MERGE,
                    dao = fixture.dao,
                    repo = fixture.repository,
                    selectedSections = setOf(BackupRestore.BackupSection.SETTINGS),
                    settingsWriter = { _, _ ->
                        // The journal is written by now, and startup must not skip it.
                        assertNotNull(fixture.dao.getRestoreJournal())
                        assertFalse(RestoreSentinel.isClean(context))
                    },
                )

            assertTrue(result.message, result.success)
            // Only a startup check proves the state clean again.
            assertFalse(RestoreSentinel.isClean(context))
        }

    @Test
    fun `a restore that can't withdraw the proof never writes a journal`() =
        runBlocking {
            // A non-empty directory under the sentinel's name can't be deleted.
            sentinel.mkdirs()
            File(sentinel, "blocker").createNewFile()

            val result =
                BackupRestore.restorePayload(
                    context = context,
                    payload = settingsPayload(),
                    mode = BackupRestore.RestoreMode.MERGE,
                    dao = fixture.dao,
                    repo = fixture.repository,
                    selectedSections = setOf(BackupRestore.BackupSection.SETTINGS),
                )

            assertFalse(result.success)
            assertNull(fixture.dao.getRestoreJournal())
        }

    private suspend fun settingsPayload(): BackupRestore.RestorePayload {
        val json = BackupRestore.backupToJson(BackupRestore.Backup(settings = BackupRestore.BackupSettings(blockCallsEnabled = false)))
        val preview = BackupRestore.previewRestoreJson(context, json, fixture.dao, setOf(BackupRestore.BackupSection.SETTINGS))
        return checkNotNull(preview.preview) { preview.message }.payload
    }
}
