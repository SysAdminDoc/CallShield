package com.sysadmindoc.callshield.service

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sysadmindoc.callshield.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A privacy-local view of WorkManager's latest persisted execution state. */
internal data class WorkerDiagnostic(
    @param:StringRes val labelRes: Int,
    val state: WorkInfo.State?,
    val runAttemptCount: Int,
    val stopReason: Int,
) {
    /** Two or more consecutive quota stops mean scheduled protection may be stale. */
    val hasRepeatedQuotaStops: Boolean
        get() = stopReason == WorkInfo.STOP_REASON_QUOTA && runAttemptCount >= 2
}

internal object WorkerDiagnostics {
    private data class TrackedWorker(
        val uniqueName: String,
        @param:StringRes val labelRes: Int,
    )

    private val trackedWorkers =
        listOf(
            TrackedWorker(BackgroundWorkNames.SYNC, R.string.protection_test_worker_sync),
            TrackedWorker(BackgroundWorkNames.HOT_LIST, R.string.protection_test_worker_hot_list),
            TrackedWorker(BackgroundWorkNames.DIGEST, R.string.protection_test_worker_digest),
            TrackedWorker(BackgroundWorkNames.PROTECTION_HEALTH, R.string.protection_test_worker_protection_health),
        )

    /**
     * WorkManager persists this metadata locally. The query is intentionally
     * read-only; no worker diagnostics leave the device.
     */
    suspend fun read(context: Context): List<WorkerDiagnostic> =
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            trackedWorkers.map { tracked ->
                val info =
                    runCatching {
                        workManager
                            .getWorkInfosForUniqueWork(tracked.uniqueName)
                            .get()
                            .firstOrNull()
                    }.getOrNull()
                WorkerDiagnostic(
                    labelRes = tracked.labelRes,
                    state = info?.state,
                    runAttemptCount = info?.runAttemptCount ?: 0,
                    stopReason =
                        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.stopReason
                        } else {
                            WorkInfo.STOP_REASON_NOT_STOPPED
                        },
                )
            }
        }

    @StringRes
    fun stateLabelRes(state: WorkInfo.State?): Int =
        when (state) {
            null -> R.string.protection_test_worker_state_not_scheduled
            WorkInfo.State.ENQUEUED -> R.string.protection_test_worker_state_enqueued
            WorkInfo.State.RUNNING -> R.string.protection_test_worker_state_running
            WorkInfo.State.SUCCEEDED -> R.string.protection_test_worker_state_succeeded
            WorkInfo.State.FAILED -> R.string.protection_test_worker_state_failed
            WorkInfo.State.BLOCKED -> R.string.protection_test_worker_state_blocked
            WorkInfo.State.CANCELLED -> R.string.protection_test_worker_state_cancelled
        }

    @StringRes
    fun stopReasonLabelRes(reason: Int): Int =
        when (reason) {
            WorkInfo.STOP_REASON_NOT_STOPPED -> R.string.protection_test_worker_stop_not_stopped
            WorkInfo.STOP_REASON_QUOTA -> R.string.protection_test_worker_stop_quota
            WorkInfo.STOP_REASON_APP_STANDBY -> R.string.protection_test_worker_stop_app_standby
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> R.string.protection_test_worker_stop_background_restriction
            WorkInfo.STOP_REASON_TIMEOUT -> R.string.protection_test_worker_stop_timeout
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> R.string.protection_test_worker_stop_foreground_timeout
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> R.string.protection_test_worker_stop_connectivity
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> R.string.protection_test_worker_stop_battery
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> R.string.protection_test_worker_stop_charging
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> R.string.protection_test_worker_stop_idle
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> R.string.protection_test_worker_stop_storage
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> R.string.protection_test_worker_stop_cancelled
            WorkInfo.STOP_REASON_PREEMPT -> R.string.protection_test_worker_stop_preempted
            WorkInfo.STOP_REASON_DEVICE_STATE -> R.string.protection_test_worker_stop_device_state
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> R.string.protection_test_worker_stop_system_processing
            WorkInfo.STOP_REASON_USER -> R.string.protection_test_worker_stop_user
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> R.string.protection_test_worker_stop_launch_estimate
            else -> R.string.protection_test_worker_stop_unknown
        }
}

/** Shared names keep WorkManager queries aligned with each worker's schedule. */
internal object BackgroundWorkNames {
    const val SYNC = "callshield_sync"
    const val HOT_LIST = "callshield_hot_list_sync"
    const val DIGEST = "callshield_digest"
    const val PROTECTION_HEALTH = "callshield_protection_health"
}
