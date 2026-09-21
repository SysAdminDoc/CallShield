package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
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
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun factory(transport: suspend (String, String, SmsReportIndicators?) -> ContributeResult) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = CommunityReportWorker(appContext, workerParameters, transport)
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
                    factory { number, type, _ ->
                        sent += "$number:$type"
                        ContributeResult(true, "ok", ContributeOutcome.REPORTED_SPAM)
                    },
                ).build(),
        )
        val workManager = WorkManager.getInstance(context)

        CommunityReportWorker.enqueue(context, number, "spam", null)
        CommunityReportWorker.enqueue(context, number, "spam", null)
        val waiting = workManager.getWorkInfosForUniqueWork(CommunityReportWorker.uniqueName(number, "spam")).get()

        assertEquals(1, waiting.size)
        assertEquals("nothing is sent without a network", emptyList<String>(), sent)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(waiting.single().id)

        assertEquals(listOf("$number:spam"), sent)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(waiting.single().id).get()!!.state)
    }

    @Test
    fun `the outbox waits for a network and backs off`() {
        val spec = CommunityReportWorker.request(number, "spam", null).workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(true, spec.backoffDelayDuration > 0)
    }

    @Test
    fun `a failure that waiting can fix is retried and one that it can't is dropped`() {
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.NETWORK_ERROR))
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.RATE_LIMITED))
        assertEquals(ListenableWorker.Result.retry(), run(ContributeOutcome.SERVER_ERROR))
        assertEquals(ListenableWorker.Result.failure(), run(ContributeOutcome.INVALID_NUMBER))
        assertEquals(
            ListenableWorker.Result.failure(),
            run(ContributeOutcome.NETWORK_ERROR, attempt = CommunityReportWorker.MAX_ATTEMPTS - 1),
        )
    }

    @Test
    fun `SMS indicators travel with the queued report`() {
        var delivered: SmsReportIndicators? = null
        val indicators = SmsReportIndicators(domains = listOf("bad.example"), urlIndicators = listOf("url_present"))
        val worker =
            TestListenableWorkerBuilder<CommunityReportWorker>(context)
                .setInputData(CommunityReportWorker.request(number, "sms_spam", indicators).workSpec.input)
                .setWorkerFactory(
                    factory { _, _, received ->
                        delivered = received
                        ContributeResult(true, "ok", ContributeOutcome.REPORTED_SPAM)
                    },
                ).build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertEquals(indicators, delivered)
    }

    private fun run(
        outcome: ContributeOutcome,
        attempt: Int = 0,
    ): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<CommunityReportWorker>(context)
                .setInputData(CommunityReportWorker.request(number, "spam", null).workSpec.input)
                .setRunAttemptCount(attempt)
                .setWorkerFactory(factory { _, _, _ -> ContributeResult(false, outcome.name, outcome) })
                .build()
        return runBlocking { worker.doWork() }
    }
}
