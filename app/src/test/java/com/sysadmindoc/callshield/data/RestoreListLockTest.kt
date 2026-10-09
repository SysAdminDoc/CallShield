package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A list refresh reads the subscribed lists and writes them back whole, so a
 * restore that wrote its lists while a refresh was downloading could lose
 * them to the refresh's older copy. A restore waits for the refresh now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RestoreListLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = GatedFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a restore waits for a list refresh and both keep their changes`() =
        runBlocking {
            // The first restore pays for loading the backup code, so the timed one below is quick.
            restore(BackupRestore.BackupSettings())
            val id = ExternalBlocklistParser.idForUrl(MY_LIST)
            fixture.settingsStore.edit {
                it[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS] =
                    """[{"id":"$id","label":"Mine","url":"$MY_LIST","enabled":true,"lastSyncedAt":1}]"""
            }
            val refresh = async(Dispatchers.IO) { fixture.repository.refreshDueExternalBlocklists() }
            feed.started.await()

            val restore =
                async(Dispatchers.IO) {
                    restore(BackupRestore.BackupSettings(externalBlocklists = listOf(BackupRestore.BackupExternalBlocklist(THEIR_LIST, "Theirs"))))
                }

            assertNull("the restore ran while a refresh was downloading", withTimeoutOrNull(1_000) { restore.await() })
            feed.release.complete(Unit)
            refresh.await()
            assertTrue(restore.await().success)

            val lists = fixture.settingsStore.data.first().externalBlocklistSubscriptions()
            assertEquals(setOf(MY_LIST, THEIR_LIST), lists.map { it.url }.toSet())
            assertTrue("the refresh's result is kept too", lists.single { it.url == MY_LIST }.lastSyncedAt > 1)
        }

    private suspend fun restore(settings: BackupRestore.BackupSettings) =
        BackupRestore.restoreWithUndo(
            context,
            BackupRestore.RestorePayload(settings = settings),
            BackupRestore.RestoreMode.REPLACE,
            fixture.dao,
            fixture.repository,
            setOf(BackupRestore.BackupSection.SETTINGS),
        )

    /** Holds the download open until the test lets it finish. */
    private class GatedFeed : ExternalBlocklistDataSource {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun fetchText(url: String): Result<String> {
            started.complete(Unit)
            release.await()
            return Result.success("+12125550101\n+12125550102")
        }
    }

    private companion object {
        const val MY_LIST = "https://lists.example.test/mine.txt"
        const val THEIR_LIST = "https://lists.example.test/theirs.txt"
    }
}
