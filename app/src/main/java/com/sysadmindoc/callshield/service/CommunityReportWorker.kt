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
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import java.util.concurrent.TimeUnit

/**
 * The community-report outbox. A report that couldn't be sent when it was
 * made (offline, rate-limited, a server error) waits here for a network and
 * is delivered once. Work is unique per number and vote type, so queueing
 * the same report again while one is waiting does nothing.
 *
 * Reports from notification actions used to be fired and forgotten, so one
 * made offline or while rate-limited was silently lost.
 */
class CommunityReportWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val transport: suspend (String, String, SmsReportIndicators?) -> ContributeResult,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, CommunityContributor::send)

    override suspend fun doWork(): Result {
        val number = inputData.getString(KEY_NUMBER) ?: return Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val indicators =
            SmsReportIndicators(
                domains = inputData.getStringArray(KEY_SMS_DOMAINS)?.toList().orEmpty(),
                urlIndicators = inputData.getStringArray(KEY_SMS_URL_INDICATORS)?.toList().orEmpty(),
            )
        val result = transport(number, type, indicators.takeUnless { it.isEmpty() })
        return when {
            result.success -> Result.success()
            !result.outcome.isTransient -> Result.failure()
            runAttemptCount + 1 >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_NUMBER = "number"
        private const val KEY_TYPE = "type"
        private const val KEY_SMS_DOMAINS = "sms_domains"
        private const val KEY_SMS_URL_INDICATORS = "sms_url_indicators"
        private const val BACKOFF_MINUTES = 5L

        /** Attempts that ran with a network; waiting offline doesn't use them up. */
        internal const val MAX_ATTEMPTS = 12

        internal fun uniqueName(
            number: String,
            type: String,
        ) = "community_report:$number:$type"

        fun enqueue(
            context: Context,
            number: String,
            type: String,
            indicators: SmsReportIndicators?,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(number, type),
                ExistingWorkPolicy.KEEP,
                request(number, type, indicators),
            )
        }

        internal fun request(
            number: String,
            type: String,
            indicators: SmsReportIndicators?,
        ): OneTimeWorkRequest {
            val input =
                Data
                    .Builder()
                    .putString(KEY_NUMBER, number)
                    .putString(KEY_TYPE, type)
            indicators?.domains?.takeIf { it.isNotEmpty() }?.let { input.putStringArray(KEY_SMS_DOMAINS, it.toTypedArray()) }
            indicators?.urlIndicators?.takeIf { it.isNotEmpty() }?.let {
                input.putStringArray(KEY_SMS_URL_INDICATORS, it.toTypedArray())
            }
            return OneTimeWorkRequestBuilder<CommunityReportWorker>()
                .setInputData(input.build())
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
        }
    }
}
