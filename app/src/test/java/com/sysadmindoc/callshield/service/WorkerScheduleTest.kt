package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** A plain Application keeps CallShieldApp's own WorkManager setup out of the test WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorkerScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every feed call fails, however the feed interface changes, so nothing here reaches the network. */
    private val unreachableFeeds =
        Proxy.newProxyInstance(
            HotFeedDataSource::class.java.classLoader,
            arrayOf(HotFeedDataSource::class.java),
        ) { _, _, _ -> throw IllegalStateException("feeds unreachable") } as HotFeedDataSource

    @Test
    fun syncWorkerPeriodicRequestKeepsNetworkAndBackoffContract() {
        val spec = SyncWorker.periodicRequest().workSpec

        assertEquals(TimeUnit.HOURS.toMillis(6), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.backoffDelayDuration)
    }

    @Test
    fun manualSyncRequestRequiresNetwork() {
        val spec = SyncWorker.syncNowRequest().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(0L, spec.intervalDuration)
    }

    @Test
    fun hotListPeriodicRequestKeepsFastNetworkRefreshContract() {
        val spec = HotListSyncWorker.periodicRequest().workSpec

        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(5), spec.backoffDelayDuration)
    }

    @Test
    fun externalBlocklistRefreshRunsEverySixHoursOnANetwork() {
        val spec = ExternalBlocklistRefreshWorker.periodicRequest().workSpec

        assertEquals(TimeUnit.HOURS.toMillis(6), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun digestPeriodicRequestKeepsDailyDigestDelay() {
        val spec = DigestWorker.periodicRequest().workSpec

        assertEquals(TimeUnit.HOURS.toMillis(24), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(1), spec.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun pendingBlockedCallLogRequestDoesNotRequireNetworkAndRetries() {
        val spec = PendingBlockedCallLogWorker.pendingRequest().workSpec

        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(1), spec.backoffDelayDuration)
    }

    @Test
    fun protectionHealthUsesDailyChecksAndAnImmediateRecoveryRequest() {
        val periodic = ProtectionHealthWorker.periodicRequest().workSpec
        val immediate = ProtectionHealthWorker.immediateRequest().workSpec

        assertEquals(TimeUnit.HOURS.toMillis(24), periodic.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(24), periodic.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, periodic.constraints.requiredNetworkType)
        assertEquals(0L, immediate.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, immediate.constraints.requiredNetworkType)
    }

    @Test
    fun appUpdateUsesWeeklyConnectedChecksAndExponentialBackoff() {
        val spec = AppUpdateWorker.periodicRequest().workSpec

        assertEquals(TimeUnit.DAYS.toMillis(7), spec.intervalDuration)
        assertEquals(TimeUnit.DAYS.toMillis(7), spec.initialDelay)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.backoffDelayDuration)
    }

    @Test
    fun manualAppUpdateCheckRequiresNetwork() {
        val spec = AppUpdateWorker.immediateRequest().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(0L, spec.initialDelay)
    }

    @Test
    fun `every periodic worker takes a changed spec on an existing install without losing its place`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

        assertUpdatedInPlace(SyncWorker.WORK_NAME, SyncWorker::class.java, SyncWorker.periodicRequest()) { SyncWorker.schedule(it) }
        assertUpdatedInPlace(HotListSyncWorker.WORK_NAME, HotListSyncWorker::class.java, HotListSyncWorker.periodicRequest()) {
            HotListSyncWorker.schedule(it)
        }
        assertUpdatedInPlace(DigestWorker.WORK_NAME, DigestWorker::class.java, DigestWorker.periodicRequest()) { DigestWorker.schedule(it) }
        assertUpdatedInPlace(
            ExternalBlocklistRefreshWorker.WORK_NAME,
            ExternalBlocklistRefreshWorker::class.java,
            ExternalBlocklistRefreshWorker.periodicRequest(),
        ) { ExternalBlocklistRefreshWorker.schedule(it) }
        assertUpdatedInPlace(BackgroundWorkNames.PROTECTION_HEALTH, ProtectionHealthWorker::class.java, ProtectionHealthWorker.periodicRequest()) {
            ProtectionHealthWorker.schedule(it)
        }
        assertUpdatedInPlace(AppUpdateWorker.PERIODIC_WORK_NAME, AppUpdateWorker::class.java, AppUpdateWorker.periodicRequest()) {
            AppUpdateWorker.schedule(it)
        }
    }

    @Test
    fun `the hot list gives up inside its own period and leaves the next run to try again`() {
        IsolatedRepositoryFixture(context).use { fixture ->
            assertEquals(ListenableWorker.Result.retry(), runHotList(fixture, attempt = 0))
            assertEquals(ListenableWorker.Result.retry(), runHotList(fixture, attempt = HotListSyncWorker.MAX_ATTEMPTS - 2))
            assertEquals(ListenableWorker.Result.failure(), runHotList(fixture, attempt = HotListSyncWorker.MAX_ATTEMPTS - 1))
        }
    }

    @Test
    fun `pending blocked-call logs that keep failing stop retrying after a fixed number of attempts`() {
        IsolatedRepositoryFixture(context).use { fixture ->
            fixture.breakDatabase()

            assertEquals(ListenableWorker.Result.retry(), runPendingLogs(fixture, attempt = 0))
            assertEquals(ListenableWorker.Result.retry(), runPendingLogs(fixture, attempt = PendingBlockedCallLogWorker.MAX_ATTEMPTS - 2))
            assertEquals(ListenableWorker.Result.failure(), runPendingLogs(fixture, attempt = PendingBlockedCallLogWorker.MAX_ATTEMPTS - 1))
        }
    }

    /**
     * Seeds the queue the way an older build left it, on twice the interval,
     * then schedules the way this build does. KEEP would leave the old
     * interval in place, and a replacing policy would cancel the queued work
     * and enqueue it again under a new id.
     */
    private fun assertUpdatedInPlace(
        uniqueName: String,
        worker: Class<out ListenableWorker>,
        current: PeriodicWorkRequest,
        schedule: (Context) -> Unit,
    ) {
        val workManager = WorkManager.getInstance(context)
        val interval = current.workSpec.intervalDuration
        val older =
            PeriodicWorkRequest
                .Builder(worker, interval * 2, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.KEEP, older).result.get()

        schedule(context)

        val work = workManager.getWorkInfosForUniqueWork(uniqueName).get().single()
        assertEquals("$uniqueName kept its queued work", older.id, work.id)
        assertEquals("$uniqueName took the new interval", interval, work.periodicityInfo!!.repeatIntervalMillis)
    }

    private fun runHotList(
        fixture: IsolatedRepositoryFixture,
        attempt: Int,
    ): ListenableWorker.Result =
        runWorker(attempt) { params ->
            HotListSyncWorker(context, params, fixture.repository, fixture.dao, unreachableFeeds, CheckerDependencies())
        }

    private fun runPendingLogs(
        fixture: IsolatedRepositoryFixture,
        attempt: Int,
    ): ListenableWorker.Result = runWorker(attempt) { params -> PendingBlockedCallLogWorker(context, params, fixture.repository) }

    private inline fun <reified W : CoroutineWorker> runWorker(
        attempt: Int,
        crossinline create: (WorkerParameters) -> W,
    ): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<W>(context)
                .setRunAttemptCount(attempt)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = create(workerParameters)
                    },
                ).build()
        return runBlocking { worker.doWork() }
    }
}
