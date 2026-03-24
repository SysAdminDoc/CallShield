package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_BLOCKED = "blocked_calls"
    const val CHANNEL_RATING = "spam_rating"
    const val CHANNEL_STATUS = "protection_status"
    const val CHANNEL_PHISHING = "phishing_warning"
    const val ACTION_BLOCK = "com.sysadmindoc.callshield.ACTION_BLOCK"
    const val ACTION_REPORT = "com.sysadmindoc.callshield.ACTION_REPORT"
    const val ACTION_SAFE = "com.sysadmindoc.callshield.ACTION_SAFE"
    const val EXTRA_NUMBER = "extra_number"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    private const val GROUP_BLOCKED = "com.sysadmindoc.callshield.BLOCKED"
    private const val SUMMARY_ID = 1
    private const val STATUS_ID = 2
    private const val RATE_LIMIT_MS = 5_000L // Min 5s between block notifications

    private var lastNotifTime = 0L
    private var blockedSinceLastNotif = 0

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Protection Status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Persistent protection status indicator"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PHISHING, "Phishing URL Warning", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alert when a received SMS contains a known malicious URL"
            }
        )
    }

    fun notifyBlocked(context: Context, number: String, reason: String, isCall: Boolean) {
        val now = System.currentTimeMillis()

        // Rate limiting — batch rapid blocks into summary
        if (now - lastNotifTime < RATE_LIMIT_MS) {
            blockedSinceLastNotif++
            updateSummary(context)
            return
        }
        lastNotifTime = now
        blockedSinceLastNotif = 0

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = stableId(number, if (isCall) 1 else 2)
        val typeText = if (isCall) "call" else "SMS"

        val openIntent = PendingIntent.getActivity(
            context, stableId(number, 40),
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_number", number)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val blockIntent = PendingIntent.getBroadcast(
            context, stableId(number, 10),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK; putExtra(EXTRA_NUMBER, number); putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reportIntent = PendingIntent.getBroadcast(
            context, stableId(number, 20),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_REPORT; putExtra(EXTRA_NUMBER, number); putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("Blocked spam $typeText")
            .setContentText("${PhoneFormatter.format(number)} ($reason)")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_BLOCKED)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Block forever", blockIntent)
            .addAction(android.R.drawable.ic_menu_send, "Report", reportIntent)
            .build()

        nm.notify(nid, notif)
        updateSummary(context)
    }

    private fun updateSummary(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val extra = if (blockedSinceLastNotif > 0) " (+$blockedSinceLastNotif more)" else ""
        val summary = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("CallShield")
            .setContentText("Blocking spam calls & texts$extra")
            .setGroup(GROUP_BLOCKED)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    fun notifySpamRating(context: Context, number: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = stableId(number, 30)

        val blockIntent = PendingIntent.getBroadcast(
            context, stableId(number, 31),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK; putExtra(EXTRA_NUMBER, number); putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val safeIntent = PendingIntent.getBroadcast(
            context, stableId(number, 32),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_SAFE; putExtra(EXTRA_NUMBER, number); putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_RATING)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Was this call spam?")
            .setContentText(PhoneFormatter.format(number))
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Spam - Block it", blockIntent)
            .addAction(android.R.drawable.ic_menu_myplaces, "Safe", safeIntent)
            .build()

        nm.notify(nid, notif)
    }

    fun notifyPhishingUrl(context: Context, sender: String, threats: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = stableId(sender, 50)

        val openIntent = PendingIntent.getActivity(
            context, stableId(sender, 51),
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_number", sender)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_PHISHING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ Phishing URL detected")
            .setContentText("SMS from ${PhoneFormatter.format(sender)} contains a $threats link")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("An SMS from ${PhoneFormatter.format(sender)} contains a URL flagged by URLhaus as $threats. Do not tap any links in this message."))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(nid, notif)
    }

    fun showPersistentStatus(context: Context, active: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!active) {
            nm.cancel(STATUS_ID)
            return
        }
        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CallShield active")
            .setContentText("Protecting against spam calls & texts")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(STATUS_ID, notif)
    }
}
