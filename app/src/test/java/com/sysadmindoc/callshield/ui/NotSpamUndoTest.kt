package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
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
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Not spam on a number's screen and in Lookup waits five seconds before the
 * allow and the report go out, so the snackbar's Undo can take it back. One
 * shared job used to hold it, so a tap on a second number dropped the first
 * one unsent. Only the network is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotSpamUndoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = "+12125550181"
    private val second = "+12125550182"
    private val fixture = IsolatedRepositoryFixture(context)
    private val sent = mutableListOf<CommunityReport>()
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor
    private lateinit var viewModel: MainViewModel

    // Every outcome, not only the last: the transport can't show a number sent
    // twice, since the contributor reports a number once a day.
    private val outcomes = mutableListOf<MainViewModel.NotSpamOutcome>()
    private var outcomeWatch: Job? = null

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
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
        outcomeWatch =
            viewModel.notSpamOutcome
                .onEach { outcome -> if (outcome != null) outcomes += outcome }
                .launchIn(CoroutineScope(Dispatchers.Main))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        outcomeWatch?.cancel()
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        // Clearing the ViewModel sends a Not spam still held, so every test
        // ends with its taps sent or taken back: a write landing while the
        // fixture deletes its database would fail under a later test.
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.close()
    }

    @Test
    fun `Not spam waits five seconds, then allows the number and sends the report`() {
        val requestId = viewModel.scheduleNotSpam(first, "Marked safe in the test")

        advance(4_900)
        settle()
        assertEquals(emptyList<String>(), sentNumbers())
        assertNull(allowed(first))

        advance(200)
        val outcome = awaitOutcome()

        assertEquals(requestId, outcome.requestId)
        assertEquals(ContributeOutcome.REPORTED_NOT_SPAM, outcome.result.outcome)
        assertEquals("Marked safe in the test", allowed(first))
        assertEquals(listOf("not_spam"), synchronized(sent) { sent.map { it.type } })
    }

    @Test
    fun `Undo before the wait ends sends nothing and allows nothing`() {
        val requestId = viewModel.scheduleNotSpam(first)
        advance(1_000)
        viewModel.undoNotSpam(requestId)
        // A tap on another number proves the wait ran out, so nothing sent
        // means taken back, not merely not yet.
        val control = viewModel.scheduleNotSpam(second)

        advance(10_000)
        awaitUntil("the control report sent") { second in sentNumbers() }
        settle()

        assertEquals(listOf(second), sentNumbers())
        assertNull(allowed(first))
        assertEquals(listOf(control), outcomes.map { it.requestId })
    }

    @Test
    fun `closing the screen during the wait sends the held Not spam at once`() {
        viewModel.scheduleNotSpam(first, "Marked safe in the test")
        advance(1_000)

        // Nobody can tap Undo on a screen that's gone, so the tap goes out
        // instead of being dropped with the ViewModel.
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        awaitUntil("the held Not spam sent") { allowed(first) != null && sentNumbers().isNotEmpty() }

        assertEquals("Marked safe in the test", allowed(first))
        assertEquals(listOf(first), sentNumbers())
    }

    @Test
    fun `the same number in two spellings is one hold`() {
        viewModel.scheduleNotSpam("+1 (212) 555-0181")
        val again = viewModel.scheduleNotSpam(first)

        advance(5_100)
        assertEquals(again, awaitOutcome().requestId)
        settle()

        assertEquals(listOf(first), sentNumbers())
        assertEquals(listOf(again), outcomes.map { it.requestId })
    }

    @Test
    fun `a tap on a second number doesn't drop the first`() {
        viewModel.scheduleNotSpam(first)
        viewModel.scheduleNotSpam(second)

        advance(5_100)
        awaitUntil("both reports sent") { sentNumbers().size == 2 && allowed(first) != null && allowed(second) != null }

        assertEquals(listOf(first, second), sentNumbers().sorted())
    }

    @Test
    fun `Undo takes back only the tap it belongs to`() {
        viewModel.scheduleNotSpam(first)
        val secondId = viewModel.scheduleNotSpam(second)
        viewModel.undoNotSpam(secondId)

        advance(5_100)
        awaitUntil("the first report sent") { allowed(first) != null && sentNumbers().isNotEmpty() }
        settle()

        assertEquals(listOf(first), sentNumbers())
        assertNull(allowed(second))
    }

    @Test
    fun `a second tap on the same number restarts its wait and sends one report`() {
        viewModel.scheduleNotSpam(first)
        advance(3_000)
        val again = viewModel.scheduleNotSpam(first)

        advance(3_000)
        settle()
        assertEquals(emptyList<String>(), sentNumbers())

        advance(2_100)
        assertEquals(again, awaitOutcome().requestId)
        settle()
        assertEquals(listOf(first), sentNumbers())
        assertEquals(listOf(again), outcomes.map { it.requestId })
    }

    @Test
    fun `Undo on the second tap of a number takes the number back`() {
        viewModel.scheduleNotSpam(first)
        val again = viewModel.scheduleNotSpam(first)
        viewModel.undoNotSpam(again)
        val control = viewModel.scheduleNotSpam(second)

        advance(10_000)
        awaitUntil("the control report sent") { second in sentNumbers() }
        settle()

        assertEquals(listOf(second), sentNumbers())
        assertNull(allowed(first))
        assertEquals(listOf(control), outcomes.map { it.requestId })
    }

    @Test
    fun `Undo on a tap a newer one replaced leaves the newer one going out`() {
        val tap = viewModel.scheduleNotSpam(first)
        val again = viewModel.scheduleNotSpam(first)
        viewModel.undoNotSpam(tap)

        advance(5_100)

        assertEquals(again, awaitOutcome().requestId)
        settle()
        assertEquals(listOf(first), sentNumbers())
    }

    private fun sentNumbers(): List<String> = synchronized(sent) { sent.map { it.number } }

    private fun allowed(number: String): String? = runBlocking { fixture.dao.findWhitelistEntry(number) }?.description

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    // The allow and the report run off the main thread once the wait ends;
    // give anything that started time to land before checking it didn't.
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
        check(done()) { "$what never happened" }
    }

    private fun awaitOutcome(): MainViewModel.NotSpamOutcome {
        awaitUntil("Not spam's outcome") { viewModel.notSpamOutcome.value != null }
        return requireNotNull(viewModel.notSpamOutcome.value)
    }
}
