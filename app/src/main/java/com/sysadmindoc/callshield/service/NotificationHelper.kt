package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sysadmindoc.callshield.ui.MainActivity

/**
 * Manages notification channels and creates block/spam rating notifications.
 * Features 4 & 5: After-call spam rating + notification quick actions.
 */
object NotificationHelper {
    const val CHANNEL_BLOCKED = "blocked_calls"
    const val CHANNEL_RATING = "spam_rating"
    const val ACTION_BLOCK = "com.sysadmindoc.callshield.ACTION_BLOCK"
    const val ACTION_REPORT = "com.sysadmindoc.callshield.ACTION_REPORT"
    const val ACTION_SAFE = "com.sysadmindoc.callshield.ACTION_SAFE"
    const val EXTRA_NUMBER = "extra_number"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    private var nextNotifId = 2000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKED, "Blocked Calls & SMS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifications when spam calls/texts are blocked"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RATING, "Spam Rating", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rate calls as spam after they end"
            }
        )
    }

    fun notifyBlocked(context: Context, number: String, reason: String, isCall: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = nextNotifId++
        val typeText = if (isCall) "call" else "SMS"

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Block permanently action
        val blockIntent = PendingIntent.getBroadcast(
            context, notifId,
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Report to community action
        val reportIntent = PendingIntent.getBroadcast(
            context, notifId + 10000,
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_REPORT
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("Blocked spam $typeText")
            .setContentText("$number ($reason)")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Block forever", blockIntent)
            .addAction(android.R.drawable.ic_menu_send, "Report", reportIntent)
            .build()

        nm.notify(notifId, notif)
    }

    /**
     * Feature 4: After-call spam rating.
     * Shows a notification after an unblocked call from an unknown number ends,
     * asking the user to rate it as spam or safe.
     */
    fun notifySpamRating(context: Context, number: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = nextNotifId++

        val blockIntent = PendingIntent.getBroadcast(
            context, notifId,
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val safeIntent = PendingIntent.getBroadcast(
            context, notifId + 20000,
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_SAFE
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_RATING)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Was this call spam?")
            .setContentText(number)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Spam - Block it", blockIntent)
            .addAction(android.R.drawable.ic_menu_myplaces, "Safe", safeIntent)
            .build()

        nm.notify(notifId, notif)
    }
}
