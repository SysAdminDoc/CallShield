package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.work.*
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
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
            val source = GitHubDataSource()
            val repo   = SpamRepository.getInstance(applicationContext)

            // ── 1. Hot numbers → Room database ───────────────────────
            val hotResult = source.fetchHotList()
            if (hotResult.isFailure) return Result.retry()

            val hotNumbers = hotResult.getOrThrow()
            if (hotNumbers.isNotEmpty()) {
                repo.replaceHotList(hotNumbers.map { hot ->
                    SpamNumber(
                        number      = repo.normalizeNumber(hot.number),
                        type        = hot.type,
                        reports     = 1,
                        description = hot.description,
                        source      = "hot_list"
                    )
                })
            }

            // ── 2. Hot ranges → SpamHeuristics in-memory ─────────────
            val ranges = source.fetchHotRanges()
            if (ranges.isNotEmpty()) {
                SpamHeuristics.updateHotRanges(ranges)
            }

            // ── 3. Spam domains → SmsContentAnalyzer in-memory ───────
            val domains = source.fetchSpamDomains()
            if (domains.isNotEmpty()) {
                SmsContentAnalyzer.updateSpamDomains(domains)
            }

            Result.success()
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
