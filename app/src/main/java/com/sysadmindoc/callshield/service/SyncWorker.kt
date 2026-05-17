package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncDatabase: SyncDatabaseUseCase,
    private val spamMLScorer: SpamMLScorer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = syncDatabase()

        // Also sync the ML model weights file — lightweight, same GitHub repo
        spamMLScorer.syncWeights(applicationContext)

        return when {
            result.success -> Result.success()
            result.shouldRetry -> Result.retry()
            else -> Result.failure() // permanent failure (e.g. HTTP 404) — don't mask as success
        }
    }

    companion object {
        private const val WORK_NAME = "callshield_sync"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest()
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueue(syncNowRequest())
        }

        internal fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

        internal fun syncNowRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .build()

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
