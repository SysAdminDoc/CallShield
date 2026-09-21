package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The outbox that stops a report made offline, or while rate-limited, from
 * being dropped. A plain Application keeps CallShieldApp's own WorkManager
 * setup out of the way of the test WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommunityReportWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12122340101"
    private val report = CommunityReport("report-1", number, "spam", null)
    private val released = mutableListOf<String>()

    private fun factory(transport: suspend (CommunityReport) -> ContributeResult) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = CommunityReportWorker(appContext, workerParameters, transport) { number, vote -> released += "$number:$vote" }
        }

    @Test
    fun `a report queued twice while offline is delivered once when the network returns`() {
        val sent = mutableListOf<String>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                // CoroutineWorker otherwise runs on Dispatchers.Default and races the assertions.
                .setWorkerCoroutineContext(Dispatchers.Unconfined)
                .setWorkerFactory(
                    factory { queuedReport ->
                        sent += "${queuedReport.id}:${queuedReport.number}:${queuedReport.type}"
                        ContributeResult(true, "ok", ContributeOutcome.REPORTED_SPAM)
                    },
                ).build(),
        )
        val workManager = WorkManager.getInstance(context)

        runBlocking {
            CommunityReportWorker.enqueue(context, report)
            CommunityReportWorker.enqueue(context, report.copy(id = "report-2", type = "robocall"))
        }
        val waiting = workManager.getWorkInfosForUniqueWork(CommunityReportWorker.uniqueName(number, "spam")).get()

        assertEquals(1, waiting.size)
        assertEquals("nothing is sent without a network", emptyList<String>(), sent)

        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(waiting.single().id)
        driver.setAllConstraintsMet(waiting.single().id)

        assertEquals(listOf("report-1:$number:spam"), sent)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(waiting.single().id).get()!!.state)
    }

    @Test
    fun `a settled report is taken back out of the outbox`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val workManager = WorkManager.getInstance(context)

        runBlocking {
            CommunityReportWorker.enqueue(context, report)
            CommunityReportWorker.dequeue(context, report)
        }

        val work = workManager.getWorkInfosForUniqueWork(CommunityReportWorker.uniqueName(number, "spam")).get().single()
        assertEquals(WorkInfo.State.CANCELLED, work.state)
    }

    @Test
    fun `the outbox waits out the direct attempt, then for a network, and backs off`() {
        val spec = CommunityReportWorker.request(report).workSpec

        assertEquals(TimeUnit.MINUTES.toMillis(CommunityReportWorker.QUEUED_DELAY_MINUTES), spec.initialDelay)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.backoffDelayDuration > 0)
    }

    @Test
    fun `a failure that waiting can fix is retried and one that it can't is dropped and released`() {
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.NETWORK_ERROR))
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.RATE_LIMITED))
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.SERVER_ERROR))
        assertEquals(emptyList<String>(), released)

        assertEquals(ListenableWorker.Result.failure(), run(ContributeOutcome.INVALID_NUMBER))
        assertEquals(
            ListenableWorker.Result.failure(),
            run(ContributeOutcome.NETWORK_ERROR, attempt = CommunityReportWorker.MAX_ATTEMPTS - 1),
        )
        // Nothing will send either of them, so neither may still count as reported today.
        assertEquals(listOf("$number:spam", "$number:spam"), released)
    }

    @Test
    fun `the report's id and SMS indicators travel with it`() {
        var delivered: CommunityReport? = null
        val indicators = SmsReportIndicators(domains = listOf("bad.example"), urlIndicators = listOf("url_present"))
        val smsReport = CommunityReport("report-7", number, "sms_spam", indicators)
        val worker =
            TestListenableWorkerBuilder<CommunityReportWorker>(context)
                .setInputData(CommunityReportWorker.request(smsReport).workSpec.input)
                .setWorkerFactory(
                    factory { received ->
                        delivered = received
                        ContributeResult(true, "ok", ContributeOutcome.REPORTED_SPAM)
                    },
                ).build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertEquals(smsReport, delivered)
    }

    @Test
    fun `a report queued before reports had ids keeps one id across its retries`() {
        val legacyInput =
            Data
                .Builder()
                .putString("number", number)
                .putString("type", "spam")
                .build()

        val first = CommunityReportWorker.reportFrom(legacyInput, fallbackId = "work-id")
        val second = CommunityReportWorker.reportFrom(legacyInput, fallbackId = "work-id")

        assertEquals("work-id", first!!.id)
        assertEquals(first, second)
    }

    private fun run(
        outcome: ContributeOutcome,
        attempt: Int = 0,
    ): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<CommunityReportWorker>(context)
                .setInputData(CommunityReportWorker.request(report).workSpec.input)
                .setRunAttemptCount(attempt)
                .setWorkerFactory(factory { ContributeResult(false, outcome.name, outcome) })
                .build()
        return runBlocking { worker.doWork() }
    }
}
