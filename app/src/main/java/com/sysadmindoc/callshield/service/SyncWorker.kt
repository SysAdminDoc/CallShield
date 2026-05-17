package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.work.*
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = SpamRepository.getInstance(applicationContext)
        val result = SyncDatabaseUseCase(SpamRepositoryAdapter(repo))()

        // Also sync the ML model weights file — lightweight, same GitHub repo
        SpamMLScorer.syncWeights(applicationContext)

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
