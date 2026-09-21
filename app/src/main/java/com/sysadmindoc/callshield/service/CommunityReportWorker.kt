package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.CommunityReportLedger
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import com.sysadmindoc.callshield.data.SpamRepository
import java.util.concurrent.TimeUnit

/**
 * The community-report outbox. Every report is queued here before its direct
 * attempt, held back by [QUEUED_DELAY_MINUTES] so the two don't race, and
 * taken out again once the direct attempt settles it. What's left (offline,
 * rate-limited, a server error, or a sender that was cancelled or killed)
 * waits for a network and is delivered once, under the id it was made with.
 * Work is unique per number and vote, so queueing the same report again while
 * one is waiting does nothing.
 *
 * Reports from notification actions used to be fired and forgotten, so one
 * made offline or while rate-limited was silently lost.
 */
class CommunityReportWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val transport: suspend (CommunityReport) -> ContributeResult,
    private val release: suspend (number: String, vote: String) -> Unit,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        CommunityContributor::send,
        { number, vote -> SpamRepository.getInstance(context.applicationContext).releaseCommunityReport(number, vote) },
    )

    override suspend fun doWork(): Result {
        val report = reportFrom(inputData, fallbackId = id.toString()) ?: return Result.failure()
        val result = transport(report)
        val givenUp = !result.success && (!result.outcome.isTransient || runAttemptCount + 1 >= MAX_ATTEMPTS)
        return when {
            result.success -> {
                Result.success()
            }

            givenUp -> {
                // Nothing will send it now, so the day's claim mustn't tell the
                // user it was already reported.
                release(report.number, CommunityReportLedger.voteOf(report.type))
                Result.failure()
            }

            else -> {
                Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_REPORT_ID = "report_id"
        private const val KEY_NUMBER = "number"
        private const val KEY_TYPE = "type"
        private const val KEY_SMS_DOMAINS = "sms_domains"
        private const val KEY_SMS_URL_INDICATORS = "sms_url_indicators"
        private const val BACKOFF_MINUTES = 5L

        /** Longer than the direct attempt's connect and read timeouts together. */
        internal const val QUEUED_DELAY_MINUTES = 2L

        /** Attempts that ran with a network; waiting offline doesn't use them up. */
        internal const val MAX_ATTEMPTS = 12

        internal fun uniqueName(
            number: String,
            vote: String,
        ) = "community_report:$number:$vote"

        private fun uniqueName(report: CommunityReport) = uniqueName(report.number, CommunityReportLedger.voteOf(report.type))

        /** Queues [report], returning once WorkManager has stored it. */
        internal suspend fun enqueue(
            context: Context,
            report: CommunityReport,
        ) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(uniqueName(report), ExistingWorkPolicy.KEEP, request(report))
                .await()
        }

        /** Takes a queued report back out after its direct attempt settled it. */
        internal suspend fun dequeue(
            context: Context,
            report: CommunityReport,
        ) {
            WorkManager
                .getInstance(context)
                .cancelUniqueWork(uniqueName(report))
                .await()
        }

        internal fun request(report: CommunityReport): OneTimeWorkRequest {
            val input =
                Data
                    .Builder()
                    .putString(KEY_REPORT_ID, report.id)
                    .putString(KEY_NUMBER, report.number)
                    .putString(KEY_TYPE, report.type)
            report.indicators
                ?.domains
                ?.takeIf { it.isNotEmpty() }
                ?.let { input.putStringArray(KEY_SMS_DOMAINS, it.toTypedArray()) }
            report.indicators?.urlIndicators?.takeIf { it.isNotEmpty() }?.let {
                input.putStringArray(KEY_SMS_URL_INDICATORS, it.toTypedArray())
            }
            return OneTimeWorkRequestBuilder<CommunityReportWorker>()
                .setInputData(input.build())
                .setInitialDelay(QUEUED_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
        }

        /**
         * The queued report, or null for input this build can't read. A report
         * queued before reports had ids uses [fallbackId], the work request's
         * own id, which stays the same across its retries.
         */
        internal fun reportFrom(
            input: Data,
            fallbackId: String,
        ): CommunityReport? {
            val number = input.getString(KEY_NUMBER) ?: return null
            val type = input.getString(KEY_TYPE) ?: return null
            val indicators =
                SmsReportIndicators(
                    domains = input.getStringArray(KEY_SMS_DOMAINS)?.toList().orEmpty(),
                    urlIndicators = input.getStringArray(KEY_SMS_URL_INDICATORS)?.toList().orEmpty(),
                )
            val id = input.getString(KEY_REPORT_ID) ?: fallbackId
            return CommunityReport(id, number, type, indicators.takeUnless { it.isEmpty() })
        }
    }
}
