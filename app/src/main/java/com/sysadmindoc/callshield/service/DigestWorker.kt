package com.sysadmindoc.callshield.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CategoryCallPolicy
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sends a daily digest notification summarizing blocked calls/SMS.
 */
@HiltWorker
class DigestWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val dao: SpamDao,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            try {
                val since = System.currentTimeMillis() - 86_400_000 // Last 24h
                val blocked = dao.getBlockedCountSinceSync(since)

                if (blocked == 0) return Result.success()

                val calls = dao.getBlockedCallCountSince(since)
                val sms = dao.getBlockedSmsCountSince(since)

                // Source breakdown from matchReason prefix — reads only the
                // short matchReason column, not full BlockedCall rows.
                val breakdown =
                    dao
                        .getBlockedMatchReasonsSince(since)
                        .groupingBy { matchReasonBucket(it) }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .joinToString(" · ") { "${it.value} ${it.key}" }

                if (!CallShieldPermissions.hasNotificationPermission(applicationContext)) {
                    // User has not granted POST_NOTIFICATIONS on API 33+; skip quietly.
                    return Result.success()
                }
                // Without a content intent the digest is inert: tapping it does
                // nothing, and setAutoCancel only takes effect on a content-intent
                // tap, so it cannot even be dismissed by tapping.
                val openIntent =
                    PendingIntent.getActivity(
                        applicationContext,
                        DIGEST_NOTIFICATION_ID,
                        Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val notif =
                    NotificationCompat
                        .Builder(applicationContext, NotificationHelper.CHANNEL_DIGEST)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle(applicationContext.getString(R.string.digest_title))
                        .setContentText(applicationContext.getString(R.string.digest_text, blocked, calls, sms))
                        .setStyle(
                            NotificationCompat
                                .BigTextStyle()
                                .bigText(applicationContext.getString(R.string.digest_big_text, blocked, calls, sms, breakdown)),
                        ).setContentIntent(openIntent)
                        .setAutoCancel(true)
                        .build()

                try {
                    NotificationManagerCompat.from(applicationContext).notify(DIGEST_NOTIFICATION_ID, notif)
                } catch (_: SecurityException) {
                    // Revoked between check and post — drop silently.
                }
                return Result.success()
            } catch (e: Exception) {
                Log.e("DigestWorker", "Failed to send daily digest", e)
                return Result.success() // Don't retry digest on failure
            }
        }

        companion object {
            private const val DIGEST_NOTIFICATION_ID = 9999
            private const val WORK_NAME = "callshield_digest"

            /**
             * Map a checker's `matchReason` to a coarse, user-facing source
             * bucket for the digest breakdown. Pure so it is unit-testable.
             */
            internal fun matchReasonBucket(reason: String): String {
                // Category-policy verdicts wrap the original checker source
                // (category_policy:<cat>:<action>:<source>) — bucket by the
                // underlying source, not the wrapper.
                val effective = CategoryCallPolicy.parseMatchSource(reason)?.originalMatchSource ?: reason
                return matchReasonBucketRaw(effective)
            }

            private fun matchReasonBucketRaw(reason: String): String =
                when {
                    reason.startsWith("database") || reason.startsWith("user_blocklist") -> "database"
                    reason.startsWith("heuristic") -> "heuristic"
                    reason.startsWith("ml_scorer") -> "ML"
                    reason.startsWith("sms_content") || reason.startsWith("keyword") -> "content"
                    reason.startsWith("rcs_") -> "RCS filter"
                    else -> "other"
                }

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }

            internal fun periodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()
        }
    }
