package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.data.SpamRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class PendingBlockedCallLogWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repo: SpamRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                repo.flushPendingBlockedCallLogs()
                if (repo.getPendingBlockedCallLogCount() == 0) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (_: Exception) {
                Result.retry()
            }

        companion object {
            private const val WORK_NAME = "callshield_pending_blocked_call_logs"

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    pendingRequest(),
                )
            }

            internal fun pendingRequest(): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<PendingBlockedCallLogWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
        }
    }
