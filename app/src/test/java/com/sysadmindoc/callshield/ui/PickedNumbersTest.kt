package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.remote.StrictOffline
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.service.CommunityReportWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * Block, Report and Not spam on the calls picked in Recent or the Blocked
 * log. Each acts on every picked number once, however it's spelled, and one
 * Undo takes back the whole batch. Only the network is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PickedNumbersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = "+12125550191"
    private val second = "+12125550192"
    private val fixture = IsolatedRepositoryFixture(context)
    private val sent = mutableListOf<CommunityReport>()
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor
    private lateinit var viewModel: MainViewModel

    private val fakeNetwork: suspend (CommunityReport) -> ContributeResult = { report ->
        synchronized(sent) { sent += report }
        val outcome = if (report.type == "not_spam") ContributeOutcome.REPORTED_NOT_SPAM else ContributeOutcome.REPORTED_SPAM
        ContributeResult(true, outcome.name, outcome)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerCoroutineContext(Dispatchers.Unconfined)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = CommunityReportWorker(appContext, workerParameters, fakeNetwork) { number, vote ->
                            fixture.repository.releaseCommunityReport(number, vote)
                        }
                    },
                ).build(),
        )
        CommunityContributor.repositoryFor = { fixture.repository }
        CommunityContributor.transport = fakeNetwork
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
    }

    @After
    fun tearDown() {
        // Clearing the ViewModel sends a Not spam still held, so nothing
        // writes while the fixture deletes its database.
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        settle()
        StrictOffline.resetForTest()
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        fixture.close()
    }

    @Test
    fun `Block blocks each picked number once and one Undo unblocks them all`() {
        val outcome = drive { viewModel.blockPickedNumbers(listOf(first, "+1 (212) 555-0191", second), "Blocked from Recent") }

        assertEquals(2, outcome.done)
        assertEquals(0, outcome.failed)
        assertEquals(2, outcome.blockUndos.size)
        assertTrue(blocked(first))
        assertTrue(blocked(second))
        assertEquals("Blocked from Recent", row(first)?.description)

        viewModel.undoSelection(outcome)
        awaitUntil("both blocks taken back") { !blocked(first) && !blocked(second) }
    }

    @Test
    fun `Block leaves an emergency number unblocked and says so`() {
        val outcome = drive { viewModel.blockPickedNumbers(listOf("911", first), "Blocked from the log") }

        assertEquals(1, outcome.done)
        assertTrue(blocked(first))
        assertNull(row("911"))
        assertEquals(context.getString(R.string.emergency_number_block_refused), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `Report sends each number once a day and blocks it here`() {
        val earlier = drive { viewModel.reportPickedNumbers(listOf(first)) }
        assertEquals(1, earlier.done)

        val outcome = drive { viewModel.reportPickedNumbers(listOf(first, "+1 (212) 555-0192", second)) }

        assertEquals(1, outcome.done)
        assertEquals(1, outcome.alreadyReported)
        assertEquals(0, outcome.failed)
        assertEquals(listOf(first, second), sentNumbers())
        assertTrue(blocked(first))
        assertTrue(blocked(second))
        // The first was blocked by the earlier report, so only the second's block is this batch's to undo.
        assertEquals(1, outcome.blockUndos.size)

        viewModel.undoSelection(outcome)
        awaitUntil("the batch's block taken back") { !blocked(second) }
        assertTrue(blocked(first))
    }

    @Test
    fun `Report keeps a block set here before as it was`() {
        runBlocking { fixture.repository.blockNumberUndoable(first, "robocall", "My own block") }

        val outcome = drive { viewModel.reportPickedNumbers(listOf(first)) }

        assertEquals(1, outcome.done)
        assertTrue(outcome.blockUndos.isEmpty())
        assertEquals("My own block", row(first)?.description)
        assertEquals("robocall", row(first)?.type)
        assertEquals(listOf(first), sentNumbers())
    }

    @Test
    fun `one Undo stops every picked Not spam`() {
        val outcome = viewModel.markPickedNotSpam(listOf(first, "+1 (212) 555-0191", second))
        assertEquals(2, outcome.done)
        assertTrue(outcome.undoable)

        viewModel.undoSelection(outcome)
        // Past the five seconds a Not spam waits for Undo.
        advance(5_100)
        settle()

        assertNull(allowed(first))
        assertNull(allowed(second))
        assertTrue(sentNumbers().isEmpty())
    }

    @Test
    fun `picked Not spam goes out for every number once the wait ends`() {
        val outcome = viewModel.markPickedNotSpam(listOf(first, second))

        // Past the five seconds a Not spam waits for Undo.
        advance(5_100)
        awaitUntil("both numbers allowed and sent") { allowed(first) != null && allowed(second) != null && sentNumbers().size == 2 }

        assertEquals(2, outcome.done)
        assertEquals(listOf(first, second), sentNumbers().sorted())
    }

    @Test
    fun `the batch's snackbar has one line per kind of result`() {
        val resources = context.resources

        assertEquals(
            "2 numbers reported and blocked\n1 was already reported in the last day\n1 couldn't be reported",
            selectionMessage(resources, SelectionAction.REPORT, MainViewModel.SelectionOutcome(done = 2, alreadyReported = 1, failed = 1)),
        )
        assertEquals(
            "1 number blocked\n1 couldn't be blocked",
            selectionMessage(resources, SelectionAction.BLOCK, MainViewModel.SelectionOutcome(done = 1, failed = 1)),
        )
        assertEquals(
            "3 numbers marked not spam",
            selectionMessage(resources, SelectionAction.NOT_SPAM, MainViewModel.SelectionOutcome(done = 3)),
        )
        assertNull(selectionMessage(resources, SelectionAction.BLOCK, MainViewModel.SelectionOutcome(done = 0)))
    }

    private fun row(number: String) = runBlocking { fixture.dao.findByNumber(number) }

    private fun blocked(number: String) = row(number)?.isUserBlocked == true

    private fun allowed(number: String): String? = runBlocking { fixture.dao.findWhitelistEntry(number) }?.description

    private fun sentNumbers(): List<String> = synchronized(sent) { sent.map { it.number } }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    // Anything the main thread started gets time to land before a check that it didn't.
    private fun settle() {
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun awaitUntil(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("$what never happened", done())
    }

    // The batch hops back to the main thread between writes, so it runs on
    // the main looper while the test idles it; blocking the thread would stall it.
    private fun <T> drive(work: suspend () -> T): T {
        var result: Result<T>? = null
        CoroutineScope(Dispatchers.Main).launch { result = runCatching { work() } }
        awaitUntil("the batch") { result != null }
        return requireNotNull(result).getOrThrow()
    }
}
