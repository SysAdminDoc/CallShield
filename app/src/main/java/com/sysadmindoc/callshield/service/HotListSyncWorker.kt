package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Syncs lightweight real-time data from GitHub every 30 minutes:
 *
 *  - hot_numbers.json  — top 500 numbers trending in community reports (last 24h)
 *  - hot_ranges.json   — NPA-NXX prefixes with 3+ hot numbers (active campaigns)
 *  - spam_domains.json — URL domains reported in SMS spam (phishing blocklist)
 *
 * Hot list numbers go into the Room database (source="hot_list") so they
 * participate in the normal isSpam() database-match layer.
 *
 * Hot ranges and spam domains are loaded directly into SpamHeuristics and
 * SmsContentAnalyzer in-memory — no database write needed since they're
 * refreshed every 30 min anyway.
 */
@HiltWorker
class HotListSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repo: SpamRepository,
        private val dao: SpamDao,
        private val hotFeedDataSource: HotFeedDataSource,
        private val checkerDependencies: CheckerDependencies,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val outcome =
                    HotDataSync.refresh(
                        context = applicationContext,
                        source = hotFeedDataSource,
                        repo = repo,
                        dao = dao,
                        dependencies = checkerDependencies,
                    )
                if (outcome.refreshedAnyFeed || outcome.hasAnyHotProtection) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (_: Exception) {
                Result.retry()
            }

        companion object {
            private const val WORK_NAME = "callshield_hot_list_sync"

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }

            internal fun periodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<HotListSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                    .build()

            private fun networkConstraints(): Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
        }
    }
