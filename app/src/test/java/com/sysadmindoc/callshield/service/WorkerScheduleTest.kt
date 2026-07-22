package com.sysadmindoc.callshield.service

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkerScheduleTest {
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
}
