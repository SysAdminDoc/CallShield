package com.sysadmindoc.callshield.ui.screens.lookup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * More's Report spam number opens Lookup, so Lookup has to offer Report for a
 * number nothing flags yet. It used to show Report only on a flagged result,
 * which left the new number a user came to report with Block and Mark
 * trusted. Only the network is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class LookupReportTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12129460188"
    private val sent = mutableListOf<CommunityReport>()
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        fixture = IsolatedRepositoryFixture(context)
        SpamRepository.replaceInstanceForTests(fixture.repository)
        CommunityContributor.repositoryFor = { fixture.repository }
        CommunityContributor.transport = { report ->
            sent += report
            ContributeResult(true, ContributeOutcome.REPORTED_SPAM.name, ContributeOutcome.REPORTED_SPAM)
        }
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.setContent { CallShieldTheme { LookupScreen(viewModel) } }
    }

    @After
    fun tearDown() {
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        SpamRepository.replaceInstanceForTests(null)
        fixture.close()
    }

    @Test
    fun `Report on a number nothing flags blocks it here and sends the report`() {
        composeRule.onNode(hasSetTextAction()).performTextInput(number)
        composeRule.onNodeWithText("Check number").performClick()
        // The check runs on the IO dispatcher, which waitForIdle doesn't wait for.
        composeRule.waitUntil(LOOKUP_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Mark trusted")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Report").performScrollTo().performClick()
        composeRule.waitUntil(LOOKUP_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Blocked on this phone.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(listOf(number), sent.map { it.number })
        assertEquals(true, runBlocking { fixture.dao.findByNumber(number) }?.isUserBlocked)
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 5_000L
    }
}
