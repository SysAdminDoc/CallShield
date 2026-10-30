package com.sysadmindoc.callshield.ui.screens.lookup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Lookup's This is not spam and Mark trusted used to allow the number and send
 * the not-spam report the moment they were tapped, with no Undo. They now wait
 * five seconds behind an Undo snackbar like the number's own screen, and a
 * not-spam result another screen asked for doesn't show here. Only the network
 * is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class LookupNotSpamTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val restoration = StateRestorationTester(composeRule)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12129460191"
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
            synchronized(sent) { sent += report }
            ContributeResult(true, ContributeOutcome.REPORTED_NOT_SPAM.name, ContributeOutcome.REPORTED_NOT_SPAM)
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
        restoration.setContent { CallShieldTheme { LookupScreen(viewModel) } }
    }

    @After
    fun tearDown() {
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        SpamRepository.replaceInstanceForTests(null)
        // Clearing the ViewModel sends a Not spam still held, so every test
        // ends with its taps sent or taken back: a write landing while the
        // fixture deletes its database would fail under a later test.
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.close()
    }

    @Test
    fun `Mark trusted waits behind Undo, and Undo sends nothing`() {
        lookUp(waitFor = "Mark trusted")

        composeRule.onNode(hasText("Mark trusted") and hasClickAction()).performScrollTo().performClick()
        awaitText("Marked not spam")
        assertEquals(emptyList<String>(), sentNumbers())
        assertNull(allowed())

        composeRule.onNodeWithText("Undo").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        settle()

        assertEquals(emptyList<String>(), sentNumbers())
        assertNull(allowed())
    }

    @Test
    fun `Mark trusted goes out after the wait and says so`() {
        lookUp(waitFor = "Mark trusted")

        composeRule.onNode(hasText("Mark trusted") and hasClickAction()).performScrollTo().performClick()
        awaitText("Marked not spam")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100))
        awaitText("Marked safe and reported")

        assertEquals(listOf(number), sentNumbers())
        assertEquals(listOf("not_spam"), synchronized(sent) { sent.map { it.type } })
        assertEquals("Marked safe from lookup", allowed())
    }

    @Test
    fun `This is not spam on a flagged number waits behind Undo too`() {
        runBlocking { fixture.dao.insertNumber(SpamNumber(number = number, type = "robocall", source = "github")) }
        lookUp(waitFor = "This is not spam")

        composeRule.onNode(hasText("This is not spam") and hasClickAction()).performScrollTo().performClick()
        awaitText("Marked not spam")
        assertEquals(emptyList<String>(), sentNumbers())
        assertNull(allowed())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100))
        awaitText("Marked safe and reported")
        assertEquals(listOf(number), sentNumbers())
    }

    /** The request ids lived in a plain `remember`, so a rotation during the wait forgot the tap was made here. */
    @Test
    fun `Mark trusted's result still shows after a rotation during the wait`() {
        lookUp(waitFor = "Mark trusted")

        composeRule.onNode(hasText("Mark trusted") and hasClickAction()).performScrollTo().performClick()
        awaitText("Marked not spam")
        restoration.emulateSavedInstanceStateRestore()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100))
        awaitText("Marked safe and reported")

        assertEquals(listOf(number), sentNumbers())
    }

    @Test
    fun `a not-spam result another screen asked for isn't shown here`() {
        viewModel.scheduleNotSpam(number)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100))
        // Watch the whole time the outcome is around, not only after: a
        // snackbar shown by mistake would be gone again by the end.
        var outcomeLanded = false
        var shown = false
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.notSpamOutcome.value != null) outcomeLanded = true
            if (composeRule.onAllNodes(hasText("Marked safe", substring = true)).fetchSemanticsNodes().isNotEmpty()) shown = true
            outcomeLanded && viewModel.notSpamOutcome.value == null
        }

        assertEquals(listOf(number), sentNumbers())
        assertEquals(false, shown)
    }

    private fun lookUp(waitFor: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(number)
        composeRule.onNodeWithText("Check number").performClick()
        // The check runs on the IO dispatcher, which waitForIdle doesn't wait for.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText(waitFor) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun sentNumbers(): List<String> = synchronized(sent) { sent.map { it.number } }

    private fun allowed(): String? = runBlocking { fixture.dao.findWhitelistEntry(number) }?.description

    // The allow and the report run off the main thread once the wait ends;
    // give anything that started time to land before checking it didn't.
    private fun settle() {
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
