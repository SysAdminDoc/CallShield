package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.work.*
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
class HotListSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = HotDataSync.refresh(applicationContext)
            if (outcome.refreshedAnyFeed || outcome.hasAnyHotProtection) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "callshield_hot_list_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HotListSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
