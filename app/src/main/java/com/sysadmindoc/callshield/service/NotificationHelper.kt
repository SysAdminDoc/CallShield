package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_BLOCKED = "blocked_calls"
    const val CHANNEL_RATING = "spam_rating"
    const val CHANNEL_STATUS = "protection_status"
    const val CHANNEL_PHISHING = "phishing_warning"
    const val CHANNEL_ALLOWED = "allowed_call_decisions"
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
    private val repeatedUrgentNoticeGate = OneShotNoticeGate()

    private fun stableId(number: String, salt: Int = 0): Int {
        return (number.hashCode() xor (salt * 0x9E3779B9.toInt())) and 0x7FFFFFFF
    }

    /**
     * Safely post a notification, honoring the API 33+ POST_NOTIFICATIONS runtime
     * permission. If the permission is not granted we silently drop the notification
     * instead of letting `nm.notify()` raise a SecurityException under StrictMode or
     * crash on devices that enforce the permission strictly. Channel creation does
     * not need a permission, only the actual post.
     */
    private fun safeNotify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!CallShieldPermissions.hasNotificationPermission(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Revoked at runtime between the check and the post — drop silently.
        }
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALLOWED, context.getString(R.string.notif_channel_allowed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_allowed_desc)
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

        val builder = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle(context.getString(R.string.notif_blocked_title, typeText))
            .setContentText(context.getString(R.string.notif_blocked_text, PhoneFormatter.format(number), reason))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_BLOCKED)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_block_forever), blockIntent)
            .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.notif_action_report), reportIntent)

        safeNotify(context, nid, builder)
        updateSummary(context)
    }

    private fun updateSummary(context: Context) {
        val count = synchronized(lock) { blockedSinceLastNotif }
        if (count <= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        safeNotify(context, SUMMARY_ID, summary)
    }

    fun notifyPhishingUrl(context: Context, sender: String, threats: String) {
        val nid = stableId(sender, 50)

        val openIntent = PendingIntent.getActivity(
            context, stableId(sender, 51),
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_number", sender)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PHISHING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_phishing_title))
            .setContentText(context.getString(R.string.notif_phishing_text, PhoneFormatter.format(sender), threats))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_phishing_big_text, PhoneFormatter.format(sender), threats)))
            .setContentIntent(openIntent)
            .setAutoCancel(true)

        safeNotify(context, nid, builder)
    }

    fun notifyAfterCall(context: Context, number: String) {
        // Don't show for very short numbers (short codes)
        if (number.filter { it.isDigit() }.length < 7) return

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

        val builder = NotificationCompat.Builder(context, CHANNEL_RATING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.feedback_title))
            .setContentText(context.getString(R.string.feedback_text, formatted))
            .addAction(0, context.getString(R.string.feedback_spam), spamPending)
            .addAction(0, context.getString(R.string.feedback_not_spam), notSpamPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        safeNotify(context, number.hashCode() + 10000, builder)
    }

    fun notifyRepeatedUrgentAllowed(context: Context, number: String) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return
        if (!repeatedUrgentNoticeGate.shouldShow("repeated_urgent:$digits")) return

        val nid = stableId(number, 70)
        val formatted = PhoneFormatter.format(number)
        val openIntent = PendingIntent.getActivity(
            context, stableId(number, 71),
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_number", number)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val blockIntent = PendingIntent.getBroadcast(
            context, stableId(number, 72),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val safeIntent = PendingIntent.getBroadcast(
            context, stableId(number, 73),
            Intent(context, SpamActionReceiver::class.java).apply {
                action = ACTION_SAFE
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTIF_ID, nid)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALLOWED)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.notif_repeated_urgent_title))
            .setContentText(context.getString(R.string.notif_repeated_urgent_text, formatted))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notif_repeated_urgent_big_text, formatted)
                )
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_block_forever), blockIntent)
            .addAction(android.R.drawable.ic_menu_save, context.getString(R.string.notif_repeated_urgent_action_safe), safeIntent)

        safeNotify(context, nid, builder)
    }

    fun showPersistentStatus(context: Context, active: Boolean) {
        if (!active) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(STATUS_ID)
            return
        }
        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(context.getString(R.string.notif_status_title))
            .setContentText(context.getString(R.string.notif_status_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
        safeNotify(context, STATUS_ID, builder)
    }
}
