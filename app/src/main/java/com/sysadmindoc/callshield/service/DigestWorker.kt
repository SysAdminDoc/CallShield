package com.sysadmindoc.callshield.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import java.util.concurrent.TimeUnit

/**
 * Sends a daily digest notification summarizing blocked calls/SMS.
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
        val dao = AppDatabase.getInstance(applicationContext).spamDao()
        val since = System.currentTimeMillis() - 86_400_000 // Last 24h
        val recent = dao.getRecentBlockedNumbers(since)
        val blocked = recent.count { it.wasBlocked }

        if (blocked == 0) return Result.success()

        val calls = recent.count { it.isCall && it.wasBlocked }
        val sms = recent.count { !it.isCall && it.wasBlocked }

        // Source breakdown from matchReason prefix
        val bySource = recent.filter { it.wasBlocked }.groupBy { call ->
            when {
                call.matchReason.startsWith("database") || call.matchReason.startsWith("user_blocklist") || call.matchReason.startsWith("hot_list") -> "database"
                call.matchReason.startsWith("heuristic") -> "heuristic"
                call.matchReason.startsWith("ml_scorer") -> "ML"
                call.matchReason.startsWith("sms_content") || call.matchReason.startsWith("keyword") -> "content"
                call.matchReason.startsWith("rcs_") -> "RCS filter"
                else -> "other"
            }
        }
        val breakdown = bySource.entries
            .sortedByDescending { it.value.size }
            .joinToString(" · ") { "${it.value.size} ${it.key}" }

        if (!CallShieldPermissions.hasNotificationPermission(applicationContext)) {
            // User has not granted POST_NOTIFICATIONS on API 33+; skip quietly.
            return Result.success()
        }
        val notif = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(applicationContext.getString(R.string.digest_title))
            .setContentText(applicationContext.getString(R.string.digest_text, blocked, calls, sms))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(applicationContext.getString(R.string.digest_big_text, blocked, calls, sms, breakdown)))
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(9999, notif)
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
        private const val WORK_NAME = "callshield_digest"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
