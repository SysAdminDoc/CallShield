package com.sysadmindoc.callshield.ui

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.domain.repository.SyncRepository
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelActionFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12125550142"
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        // Let the view model's startup reads finish while the database still works.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a block, allow or rule change that can't be written says so instead of crashing`() {
        // A missing table fails the way a full disk does, with an SQLiteException.
        // A closed database won't do here: Room 2.8 cancels its scope on close, so
        // a write after it ends in a CancellationException, which crashes nothing.
        // A startup read that lands on a dropped table only logs, so every toast is an action's.
        listOf("spam_numbers", "whitelist", "wildcard_rules").forEach(fixture::damageTable)
        val actions =
            mapOf<String, () -> Unit>(
                "block" to { viewModel.blockNumber(number, "spam") },
                "allow" to { viewModel.addToWhitelist(number) },
                "add rule" to { viewModel.addWildcardRule("+1212555*", isRegex = false, description = "") },
                "restore rule" to { viewModel.restoreWildcardRule(WildcardRule(id = 7, pattern = "+1212555*")) },
            )

        for ((name, action) in actions) {
            ShadowToast.reset()
            action()
            // The write runs off the main thread; wait for its toast.
            val deadline = System.currentTimeMillis() + 5_000
            while (ShadowToast.getTextOfLatestToast() == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }

            assertEquals(name, context.getString(R.string.action_failed), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `a sync that throws ends as an error, so Sync can be tapped again`() {
        val failingSync =
            object : SyncRepository {
                override suspend fun syncDatabase(force: Boolean): SyncResult = throw SQLiteFullException("database or disk is full")
            }
        val syncing =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(failingSync),
                manageBlocklist = ManageBlocklistUseCase(SpamRepositoryAdapter(fixture.repository)),
                exportLogs = ExportLogsUseCase(context),
            )
        shadowOf(Looper.getMainLooper()).idle()

        syncing.sync()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SyncState.Error(context.getString(R.string.action_failed)), syncing.syncState.value)
    }

    /**
     * A screen's data that throws while the screen collects it used to end on viewModelScope,
     * which has no handler, so the app closed. The first runTest turns on the collector that
     * holds such a throw, and the last one fails with UncaughtExceptionsBeforeTest if one got out.
     */
    @Test
    fun `a screen whose data can't be read shows the initial value instead of crashing`() {
        runTest { }
        ShadowLog.reset()
        fixture.damageTable("call_log")

        val screen = CoroutineScope(Dispatchers.Main.immediate).launch { viewModel.totalBlocked.collect { } }
        // Room reads off the main thread; wait for the failure to come back.
        val deadline = System.currentTimeMillis() + 5_000
        while (screenFailures().isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        screen.cancel()

        assertEquals(0, viewModel.totalBlocked.value)
        assertTrue(screenFailures().single().throwable is SQLiteException)
        runTest { }
    }

    /** Its null draws a blank surface until settings load, so a read that fails has to answer something else. */
    @Test
    fun `settings that can't be read show the app instead of a blank screen`() {
        runTest { }
        ShadowLog.reset()
        val unreadable = IsolatedRepositoryFixture(context, unreadableSettings = true)
        try {
            val shell =
                MainViewModel(
                    appContext = context,
                    repo = unreadable.repository,
                    syncDatabase = SyncDatabaseUseCase(SpamRepositoryAdapter(unreadable.repository)),
                    manageBlocklist = ManageBlocklistUseCase(SpamRepositoryAdapter(unreadable.repository)),
                    exportLogs = ExportLogsUseCase(context),
                )
            val screen = CoroutineScope(Dispatchers.Main.immediate).launch { shell.onboardingDone.collect { } }
            val deadline = System.currentTimeMillis() + 5_000
            while (shell.onboardingDone.value == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            screen.cancel()

            assertEquals(true, shell.onboardingDone.value)
            assertTrue(screenFailures().single().throwable is IOException)
        } finally {
            unreadable.close()
        }
        runTest { }
    }

    private fun screenFailures() = ShadowLog.getLogsForTag("MainViewModel").filter { it.msg == "Screen data failed" }
}
