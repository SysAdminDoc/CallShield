package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.repository.SettingsRepository
import com.sysadmindoc.callshield.service.CommunityReportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Issue #10's report arrived three times minutes apart, and one number
 * arrived 12 times in a day. The day's ledger lives in DataStore, so a
 * repeat is caught across a process death as well as across a double tap.
 * Every claimed report is either delivered, left in the outbox, or released.
 *
 * Robolectric supplies a real SDK level: on a bare JVM DataStore sees SDK 0
 * and falls back to File.renameTo, which can't replace a file on Windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommunityReportSubmitterTest {
    private val directory: File = Files.createTempDirectory("callshield-report-ledger-").toFile()
    private val stores = mutableListOf<Store>()
    private val sent = mutableListOf<CommunityReport>()
    private val queued = mutableListOf<CommunityReport>()
    private val dequeued = mutableListOf<CommunityReport>()
    private var outcome = ContributeOutcome.REPORTED_SPAM
    private var cancelSend = false
    private var now = 1_790_000_000_000L
    private var nextId = 0

    /** One process's view of the settings files. Closing it is a process death. */
    private inner class Store : AutoCloseable {
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        val settings =
            SettingsRepository(
                dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") },
                privateDataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "private.preferences_pb") },
            )

        override fun close() = runBlocking { job.cancelAndJoin() }
    }

    private fun store() = Store().also(stores::add)

    private fun submitter(store: Store) =
        CommunityReportSubmitter(
            claim = store.settings::claimCommunityReport,
            release = store.settings::releaseCommunityReport,
            transport = { report ->
                sent += report
                if (cancelSend) throw CancellationException("the screen went away")
                val delivered = outcome == ContributeOutcome.REPORTED_SPAM || outcome == ContributeOutcome.REPORTED_NOT_SPAM
                ContributeResult(delivered, outcome.name, outcome)
            },
            enqueue = { report -> queued += report },
            dequeue = { report -> dequeued += report },
            clock = { now },
            newId = { "report-${++nextId}" },
        )

    private fun Store.submit(
        number: String,
        type: String = "spam",
    ) = runBlocking { submitter(this@submit).submit(number, type, null) }

    private fun List<CommunityReport>.keys() = map { "${it.number}:${it.type}" }

    @After
    fun tearDown() {
        stores.forEach(Store::close)
        directory.deleteRecursively()
    }

    @Test
    fun `a second report of the same number and vote never reaches the network`() {
        val store = store()

        assertEquals(ContributeOutcome.REPORTED_SPAM, store.submit("+1 212 234 0101").outcome)
        val second = store.submit("+12122340101")

        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, second.outcome)
        assertTrue(second.success)
        assertEquals(listOf("+12122340101:spam"), sent.keys())
    }

    @Test
    fun `a category is not a separate vote`() {
        // A notification's Block sends "spam" and Number Detail sends the row's
        // category. Both are one person's spam vote, and the pipeline counts
        // each type as a report of its own.
        val store = store()
        store.submit("+12122340101", "spam")

        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, store.submit("+12122340101", "robocall").outcome)
        assertEquals(listOf("+12122340101:spam"), sent.keys())
    }

    @Test
    fun `the day's record survives a process death`() {
        val before = store()
        before.submit("+12122340101")
        before.close()
        stores.remove(before)

        val after = store()

        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, after.submit("+12122340101").outcome)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a correction is a different report, and a day later the same one goes out again`() {
        val store = store()
        store.submit("+12122340101", "spam")
        outcome = ContributeOutcome.REPORTED_NOT_SPAM

        assertEquals(ContributeOutcome.REPORTED_NOT_SPAM, store.submit("+12122340101", "not_spam").outcome)

        outcome = ContributeOutcome.REPORTED_SPAM
        now += TimeUnit.HOURS.toMillis(25)
        assertEquals(ContributeOutcome.REPORTED_SPAM, store.submit("+12122340101", "spam").outcome)
        assertEquals(listOf("+12122340101:spam", "+12122340101:not_spam", "+12122340101:spam"), sent.keys())
    }

    @Test
    fun `every report is queued before it is sent, and a delivered one is taken back out`() {
        val store = store()

        store.submit("+12122340101")

        assertEquals(listOf("report-1"), queued.map { it.id })
        assertEquals(listOf("report-1"), sent.map { it.id })
        assertEquals(listOf("report-1"), dequeued.map { it.id })
    }

    @Test
    fun `a report that can't go out now stays queued, once, and not dropped`() {
        val store = store()
        outcome = ContributeOutcome.NETWORK_ERROR

        val first = store.submit("+12122340101")
        val retapped = store.submit("+12122340101")

        assertEquals(ContributeOutcome.QUEUED, first.outcome)
        assertTrue(first.success)
        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, retapped.outcome)
        assertEquals(listOf("+12122340101:spam"), queued.keys())
        assertEquals(emptyList<CommunityReport>(), dequeued)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a report whose sender is cancelled mid-send is still queued and still claimed`() {
        val store = store()
        cancelSend = true

        try {
            store.submit("+12122340101")
            fail("the cancellation should reach the caller")
        } catch (_: CancellationException) {
            // The Lookup screen went away during the send.
        }

        assertEquals(listOf("+12122340101:spam"), queued.keys())
        assertEquals(emptyList<CommunityReport>(), dequeued)
        cancelSend = false
        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, store.submit("+12122340101").outcome)
    }

    @Test
    fun `a report delivered after its caller was cancelled still takes its queued copy back out`() {
        // Leaving Lookup mid-send used to throw the Worker's 200 away, and the
        // outbox then sent the report again two minutes later.
        val store = store()
        val sending = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val submitter =
            gatedSubmitter(store, transport = { report ->
                sent += report
                sending.complete(Unit)
                answered.await()
                ContributeResult(true, "stored", ContributeOutcome.REPORTED_SPAM)
            })

        runBlocking {
            val job = launch(Dispatchers.Default) { submitter.submit("+12122340101", "spam", null) }
            sending.await()
            job.cancel()
            answered.complete(Unit)
            job.join()
        }

        assertEquals(listOf("report-1"), queued.map { it.id })
        assertEquals(listOf("report-1"), dequeued.map { it.id })
    }

    @Test
    fun `a claim is never left without its queued copy when the caller is cancelled`() {
        val store = store()
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val submitter =
            gatedSubmitter(store, claim = { number, vote, time ->
                store.settings.claimCommunityReport(number, vote, time).also {
                    claimed.complete(Unit)
                    resume.await()
                }
            })

        runBlocking {
            val job = launch(Dispatchers.Default) { submitter.submit("+12122340101", "spam", null) }
            claimed.await()
            job.cancel()
            resume.complete(Unit)
            job.join()
        }

        assertEquals(listOf("report-1"), queued.map { it.id })
        assertEquals(listOf("report-1"), sent.map { it.id })
    }

    @Test
    fun `a send torn down with no queued copy gives the claim back`() {
        val store = store()
        cancelSend = true
        val submitter = gatedSubmitter(store, enqueue = { error("WorkManager is unavailable") })

        runBlocking {
            try {
                submitter.submit("+12122340101", "spam", null)
                fail("the cancellation should reach the caller")
            } catch (_: CancellationException) {
                // Nothing will deliver it now, so it mustn't read as sent.
            }
        }

        cancelSend = false
        assertEquals(ContributeOutcome.REPORTED_SPAM, store.submit("+12122340101").outcome)
    }

    @Test
    fun `the direct attempt always ends before the queued copy is due`() {
        val callTimeout = TimeUnit.SECONDS.toMillis(CommunityContributor.REPORT_CALL_TIMEOUT_SECONDS)

        assertEquals(callTimeout.toInt(), CommunityContributor.client.callTimeoutMillis)
        assertTrue(callTimeout < TimeUnit.MINUTES.toMillis(CommunityReportWorker.QUEUED_DELAY_MINUTES))
    }

    private fun gatedSubmitter(
        store: Store,
        claim: suspend (String, String, Long) -> Boolean = store.settings::claimCommunityReport,
        enqueue: suspend (CommunityReport) -> Unit = { report -> queued += report },
        transport: suspend (CommunityReport) -> ContributeResult = { report ->
            sent += report
            if (cancelSend) throw CancellationException("the send was torn down")
            ContributeResult(true, "stored", ContributeOutcome.REPORTED_SPAM)
        },
    ) = CommunityReportSubmitter(
        claim = claim,
        release = store.settings::releaseCommunityReport,
        transport = transport,
        enqueue = enqueue,
        dequeue = { report -> dequeued += report },
        clock = { now },
        newId = { "report-${++nextId}" },
    )

    @Test
    fun `a refused report leaves nothing queued and can be made again`() {
        val store = store()
        outcome = ContributeOutcome.INVALID_NUMBER

        assertEquals(ContributeOutcome.INVALID_NUMBER, store.submit("+12122340101").outcome)
        assertEquals(queued.map { it.id }, dequeued.map { it.id })

        // Released, so trying again isn't answered with "already reported".
        assertEquals(ContributeOutcome.INVALID_NUMBER, store.submit("+12122340101").outcome)
        assertEquals(2, sent.size)
    }

    @Test
    fun `an unreadable number is neither claimed nor queued`() {
        val store = store()

        assertEquals(ContributeOutcome.INVALID_NUMBER, store.submit("12").outcome)
        assertEquals(emptyList<CommunityReport>(), queued)
        assertEquals(emptyList<CommunityReport>(), sent)
    }
}
