package com.sysadmindoc.callshield.service

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerDiagnosticsTest {
    @Test
    fun `two quota stops are actionable`() {
        val diagnostic =
            WorkerDiagnostic(
                labelRes = 0,
                state = WorkInfo.State.ENQUEUED,
                runAttemptCount = 2,
                stopReason = WorkInfo.STOP_REASON_QUOTA,
            )

        assertTrue(diagnostic.hasRepeatedQuotaStops)
    }

    @Test
    fun `single quota stop does not create a repeated failure warning`() {
        val diagnostic =
            WorkerDiagnostic(
                labelRes = 0,
                state = WorkInfo.State.ENQUEUED,
                runAttemptCount = 1,
                stopReason = WorkInfo.STOP_REASON_QUOTA,
            )

        assertFalse(diagnostic.hasRepeatedQuotaStops)
    }

    @Test
    fun `ordinary retry and non quota stop remain informational`() {
        val retry =
            WorkerDiagnostic(
                labelRes = 0,
                state = WorkInfo.State.ENQUEUED,
                runAttemptCount = 4,
                stopReason = WorkInfo.STOP_REASON_NOT_STOPPED,
            )
        val timeout =
            WorkerDiagnostic(
                labelRes = 0,
                state = WorkInfo.State.FAILED,
                runAttemptCount = 2,
                stopReason = WorkInfo.STOP_REASON_TIMEOUT,
            )

        assertFalse(retry.hasRepeatedQuotaStops)
        assertFalse(timeout.hasRepeatedQuotaStops)
    }
}
