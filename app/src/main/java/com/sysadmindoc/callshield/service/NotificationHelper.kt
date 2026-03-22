package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sysadmindoc.callshield.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_BLOCKED = "blocked_calls"
    const val CHANNEL_RATING = "spam_rating"
    const val ACTION_BLOCK = "com.sysadmindoc.callshield.ACTION_BLOCK"
    const val ACTION_REPORT = "com.sysadmindoc.callshield.ACTION_REPORT"
    const val ACTION_SAFE = "com.sysadmindoc.callshield.ACTION_SAFE"
    const val EXTRA_NUMBER = "extra_number"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    // Stable notification IDs derived from number hash — no counter overflow
    private fun stableId(number: String, salt: Int = 0): Int {
        return (number.hashCode() xor (salt * 0x9E3779B9.toInt())) and 0x7FFFFFFF
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKED, "Blocked Calls & SMS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifications when spam calls/texts are blocked"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RATING, "Spam Rating", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Rate calls as spam after they end"
            }
        )
    }

    fun notifyBlocked(context: Context, number: String, reason: String, isCall: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = stableId(number, if (isCall) 1 else 2)
        val typeText = if (isCall) "call" else "SMS"

        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val blockIntent = PendingIntent.getBroadcast(
            context, stableId(number, 10),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reportIntent = PendingIntent.getBroadcast(
            context, stableId(number, 20),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_REPORT
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
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

        nm.notify(nid, notif)
    }

    fun notifySpamRating(context: Context, number: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = stableId(number, 30)

        val blockIntent = PendingIntent.getBroadcast(
            context, stableId(number, 31),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val safeIntent = PendingIntent.getBroadcast(
            context, stableId(number, 32),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_SAFE
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
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

        nm.notify(nid, notif)
    }
}
