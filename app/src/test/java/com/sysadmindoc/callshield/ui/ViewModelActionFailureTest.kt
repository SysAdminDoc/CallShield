package com.sysadmindoc.callshield.ui

import android.content.Context
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

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
}
