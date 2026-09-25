package com.sysadmindoc.callshield.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.DatabaseSourceFilter
import com.sysadmindoc.callshield.data.DatabaseTypeFilter
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseFilterStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun viewModel(state: SavedStateHandle): MainViewModel {
        val adapter = SpamRepositoryAdapter(fixture.repository)
        return MainViewModel(
            appContext = context,
            repo = fixture.repository,
            syncDatabase = SyncDatabaseUseCase(adapter),
            manageBlocklist = ManageBlocklistUseCase(adapter),
            exportLogs = ExportLogsUseCase(context),
            savedStateHandle = state,
        ).also { shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test
    fun `the database chips come back after process death`() {
        val saved = SavedStateHandle()
        viewModel(saved).setDatabaseFilter(DatabaseTypeFilter.ROBOCALL, DatabaseSourceFilter.TRENDING)

        // Process death keeps only what the handle wrote out; a new view model reads it back.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

        assertEquals(DatabaseTypeFilter.ROBOCALL to DatabaseSourceFilter.TRENDING, viewModel(restored).databaseFilter.value)
    }

    @Test
    fun `a fresh start shows every row`() {
        assertEquals(DatabaseTypeFilter.ALL to DatabaseSourceFilter.ALL, viewModel(SavedStateHandle()).databaseFilter.value)
    }

    @Test
    fun `a saved chip this version doesn't have falls back to All`() {
        val saved = SavedStateHandle()
        viewModel(saved).setDatabaseFilter(DatabaseTypeFilter.SPAM, DatabaseSourceFilter.MINE)
        val restored =
            SavedStateHandle(
                saved.keys().associateWith { key ->
                    saved.get<Any?>(key).takeUnless { it == DatabaseTypeFilter.SPAM.name } ?: "RETIRED_CHIP"
                },
            )

        assertEquals(DatabaseTypeFilter.ALL to DatabaseSourceFilter.MINE, viewModel(restored).databaseFilter.value)
    }
}
