package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sysadmindoc.callshield.R
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
    private val lock = Any()

    private fun stableId(number: String, salt: Int = 0): Int {
        return (number.hashCode() xor (salt * 0x9E3779B9.toInt())) and 0x7FFFFFFF
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKED, context.getString(R.string.notif_channel_blocked), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_blocked_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RATING, context.getString(R.string.notif_channel_rating), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_rating_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notif_channel_status_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PHISHING, context.getString(R.string.notif_channel_phishing), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_phishing_desc)
            }
        )
    }

    fun notifyBlocked(context: Context, number: String, reason: String, isCall: Boolean) {
        val now = System.currentTimeMillis()
        val nid = stableId(number, if (isCall) 1 else 2)

        // Rate limiting — batch rapid blocks into summary (synchronized to avoid race)
        synchronized(lock) {
            if (now - lastNotifTime < RATE_LIMIT_MS) {
                blockedSinceLastNotif++
                updateSummary(context)
                return
            }
            lastNotifTime = now
            blockedSinceLastNotif = 1
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val typeText = context.getString(if (isCall) R.string.notif_type_call else R.string.notif_type_sms)

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
            .setContentTitle(context.getString(R.string.notif_blocked_title, typeText))
            .setContentText(context.getString(R.string.notif_blocked_text, PhoneFormatter.format(number), reason))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_BLOCKED)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_block_forever), blockIntent)
            .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.notif_action_report), reportIntent)
            .build()

        nm.notify(nid, notif)
        updateSummary(context)
    }

    private fun updateSummary(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val count = synchronized(lock) { blockedSinceLastNotif }
        if (count <= 0) {
            nm.cancel(SUMMARY_ID)
            return
        }
        val summaryText = context.resources.getQuantityString(R.plurals.notif_summary_text_recent, count, count)
        val summary = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryText)
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
            .setContentTitle(context.getString(R.string.notif_rating_title))
            .setContentText(PhoneFormatter.format(number))
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_spam_block), blockIntent)
            .addAction(android.R.drawable.ic_menu_myplaces, context.getString(R.string.notif_action_safe), safeIntent)
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
            .setContentTitle(context.getString(R.string.notif_phishing_title))
            .setContentText(context.getString(R.string.notif_phishing_text, PhoneFormatter.format(sender), threats))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_phishing_big_text, PhoneFormatter.format(sender), threats)))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(nid, notif)
    }

    fun notifyAfterCall(context: Context, number: String) {
        // Don't show for very short numbers (short codes)
        if (number.filter { it.isDigit() }.length < 7) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intents for "Spam" and "Not Spam" actions
        val spamIntent = Intent(context, SpamActionReceiver::class.java).apply {
            action = "com.sysadmindoc.callshield.FEEDBACK_SPAM"
            putExtra("number", number)
        }
        val notSpamIntent = Intent(context, SpamActionReceiver::class.java).apply {
            action = "com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM"
            putExtra("number", number)
        }

        val spamPending = PendingIntent.getBroadcast(context, number.hashCode(), spamIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notSpamPending = PendingIntent.getBroadcast(context, number.hashCode() + 1, notSpamIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val formatted = PhoneFormatter.format(number)

        val notification = NotificationCompat.Builder(context, CHANNEL_RATING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.feedback_title))
            .setContentText(context.getString(R.string.feedback_text, formatted))
            .addAction(0, context.getString(R.string.feedback_spam), spamPending)
            .addAction(0, context.getString(R.string.feedback_not_spam), notSpamPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(number.hashCode() + 10000, notification)
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
            .setContentTitle(context.getString(R.string.notif_status_title))
            .setContentText(context.getString(R.string.notif_status_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(STATUS_ID, notif)
    }
}
