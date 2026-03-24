package com.sysadmindoc.callshield.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.sysadmindoc.callshield.data.local.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Sends a daily digest notification summarizing blocked calls/SMS.
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
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

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("CallShield Daily Summary")
            .setContentText("Blocked $blocked spam today ($calls calls, $sms texts)")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Blocked $blocked spam today — $calls calls, $sms texts\n$breakdown"))
            .setAutoCancel(true)
            .build()

        nm.notify(9999, notif)
        return Result.success()
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
