package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.service.CommunityReportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production entry points, [CommunityContributor.contribute] and
 * [CommunityContributor.reportNotSpam], with the real ledger and the real
 * outbox. Only the network is faked, so nothing here reaches the Worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommunityContributorWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)
    private val sent = mutableListOf<CommunityReport>()
    private var outcome = ContributeOutcome.REPORTED_SPAM
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor

    private val fakeNetwork: suspend (CommunityReport) -> ContributeResult = { report ->
        sent += report
        val delivered = outcome == ContributeOutcome.REPORTED_SPAM || outcome == ContributeOutcome.REPORTED_NOT_SPAM
        ContributeResult(delivered, outcome.name, outcome)
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
    }

    @After
    fun tearDown() {
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        fixture.close()
    }

    @Test
    fun `a report goes through the ledger and the outbox, and a repeat never reaches the network`() {
        val first = runBlocking { CommunityContributor.contribute(context, "+12122340101", "robocall") }
        val second = runBlocking { CommunityContributor.contribute(context, "+1 212 234 0101", "spam") }

        assertEquals(ContributeOutcome.REPORTED_SPAM, first.outcome)
        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, second.outcome)
        assertEquals(1, sent.size)
        assertEquals("delivered, so taken back out", WorkInfo.State.CANCELLED, work("+12122340101", "spam").state)
    }

    @Test
    fun `a report that can't go out now is delivered later under the same id`() {
        outcome = ContributeOutcome.NETWORK_ERROR

        val result = runBlocking { CommunityContributor.reportNotSpam(context, "+12122340101") }

        assertEquals(ContributeOutcome.QUEUED, result.outcome)
        val waiting = work("+12122340101", "not_spam")
        assertEquals(WorkInfo.State.ENQUEUED, waiting.state)

        outcome = ContributeOutcome.REPORTED_NOT_SPAM
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(waiting.id)
        driver.setAllConstraintsMet(waiting.id)

        assertEquals(2, sent.size)
        assertEquals("one report, one id", sent[0].id, sent[1].id)
        assertEquals(WorkInfo.State.SUCCEEDED, work("+12122340101", "not_spam").state)
    }

    @Test
    fun `a refused report can be made again the same day`() {
        outcome = ContributeOutcome.INVALID_NUMBER

        runBlocking { CommunityContributor.contribute(context, "+12122340101") }
        val again = runBlocking { CommunityContributor.contribute(context, "+12122340101") }

        assertEquals(ContributeOutcome.INVALID_NUMBER, again.outcome)
        assertEquals(2, sent.size)
    }

    private fun work(
        number: String,
        vote: String,
    ): WorkInfo =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(CommunityReportWorker.uniqueName(number, vote))
            .get()
            .single()
}
