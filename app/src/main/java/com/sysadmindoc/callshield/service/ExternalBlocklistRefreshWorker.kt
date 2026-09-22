package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.data.ExternalBlocklistRefreshPolicy
import com.sysadmindoc.callshield.data.SpamRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keeps external blocklist subscriptions current. A list used to be fetched
 * only when it was added or switched back on, so one that changes daily went
 * stale on the device indefinitely. Each run fetches only the lists that are
 * due, so a run with nothing due costs one settings read.
 */
@HiltWorker
class ExternalBlocklistRefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repo: SpamRepository,
    ) : CoroutineWorker(context, params) {
        internal var clock: () -> Long = System::currentTimeMillis

        override suspend fun doWork(): Result {
            // A list that fails or is held back says so on its own row and is
            // tried again when next due. Retrying the whole run would only
            // fetch the healthy lists again.
            repo.refreshDueExternalBlocklists(clock())
            return Result.success()
        }

        companion object {
            internal const val WORK_NAME = BackgroundWorkNames.EXTERNAL_BLOCKLISTS

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }

            internal fun periodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<ExternalBlocklistRefreshWorker>(
                    ExternalBlocklistRefreshPolicy.MIN_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                ).setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build()
        }
    }
