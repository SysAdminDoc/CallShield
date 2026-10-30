package com.sysadmindoc.callshield.ui.screens.details

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The number's screen kept its Report and Not spam request ids in a plain
 * `remember`, so a rotation while a tap was out forgot the tap was made here
 * and its result, Report's Undo included, never showed. Only the network is
 * faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class NumberDetailRotationTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val restoration = StateRestorationTester(composeRule)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12129460192"
    private val sent = mutableListOf<CommunityReport>()
    private val release = CountDownLatch(1)
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
            val outcome = if (report.type == "not_spam") ContributeOutcome.REPORTED_NOT_SPAM else ContributeOutcome.REPORTED_SPAM
            // A spam report waits for the test, so the screen can be recreated while it's out.
            // The wait is on IO like the real send: the caller is the main thread, which is
            // the test's own, and blocking it there let the report finish before the rotation.
            if (outcome == ContributeOutcome.REPORTED_SPAM) withContext(Dispatchers.IO) { release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            synchronized(sent) { sent += report }
            ContributeResult(true, outcome.name, outcome)
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
        restoration.setContent { CallShieldTheme { NumberDetailScreen(number, viewModel, onBack = {}) } }
        // The live check's verdict card loads off the main thread and pushes the
        // buttons down. Arriving between the scroll and the tap, it left the
        // button below the window and the tap landed on nothing.
        awaitText("No risk detected")
    }

    @After
    fun tearDown() {
        release.countDown()
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        SpamRepository.replaceInstanceForTests(null)
        // Clearing the ViewModel sends a Not spam still held, so nothing writes
        // while the fixture deletes its database.
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.close()
    }

    @Test
    fun `Not spam's result still shows after a rotation during the wait`() {
        composeRule.onNode(hasText("Not spam") and hasClickAction()).performScrollTo().performClick()
        awaitText("Marked not spam")
        restoration.emulateSavedInstanceStateRestore()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100))
        awaitText("Reported as not spam")

        assertEquals(listOf("not_spam"), synchronized(sent) { sent.map { it.type } })
    }

    @Test
    fun `Report's result and its Undo still show after a rotation while it goes out`() {
        composeRule.onNode(hasText("Report") and hasClickAction()).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(emptyList<CommunityReport>(), synchronized(sent) { sent.toList() })
        release.countDown()
        awaitText("Blocked on this phone.", substring = true)

        composeRule.onNodeWithText("Undo").assertExists()
        assertEquals(listOf(number), synchronized(sent) { sent.map { it.number } })
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
    ) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
