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
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainActivity
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.filterAsciiDigitsLast

private const val STABLE_ID_MULTIPLIER = 0x9E3779B9.toInt()
private const val STABLE_ID_MASK = 0x7FFFFFFF

private fun stableId(
    number: String,
    salt: Int = 0,
): Int = (number.hashCode() xor (salt * STABLE_ID_MULTIPLIER)) and STABLE_ID_MASK

private fun reportableSmsIndicators(
    isCall: Boolean,
    smsBody: String?,
): SmsContentAnalyzer.SmsReportIndicators =
    if (!isCall && !smsBody.isNullOrBlank()) {
        SmsContentAnalyzer.extractReportableIndicators(smsBody)
    } else {
        SmsContentAnalyzer.SmsReportIndicators()
    }

object NotificationHelper {
    const val CHANNEL_BLOCKED = "blocked_calls"
    const val CHANNEL_RATING = "spam_rating"
    const val CHANNEL_STATUS = "protection_status"
    const val CHANNEL_PHISHING = "phishing_warning"
    const val CHANNEL_ALLOWED = "allowed_call_decisions"
    const val CHANNEL_DIGEST = "daily_digest"
    const val ACTION_BLOCK = "com.sysadmindoc.callshield.ACTION_BLOCK"
    const val ACTION_REPORT = "com.sysadmindoc.callshield.ACTION_REPORT"
    const val ACTION_SAFE = "com.sysadmindoc.callshield.ACTION_SAFE"
    const val EXTRA_NUMBER = "extra_number"
    const val EXTRA_NOTIF_ID = "extra_notif_id"
    const val EXTRA_IS_CALL = "extra_is_call"
    const val EXTRA_SMS_DOMAINS = "extra_sms_domains"
    const val EXTRA_SMS_URL_INDICATORS = "extra_sms_url_indicators"

    private const val GROUP_BLOCKED = "com.sysadmindoc.callshield.BLOCKED"
    private const val SUMMARY_ID = 1
    private const val STATUS_ID = 2
    private const val RATE_LIMIT_MS = 5_000L // Min 5s between block notifications
    private const val SMS_SAFE_ACTION_SALT = 30
    private const val FEEDBACK_ID_SALT = 62

    /**
     * Notification ID for the after-call "Was this spam?" feedback notice.
     * Single source of truth so the posting site and the action receiver
     * (which must cancel it after the user taps Spam/Not-Spam) always agree.
     */
    fun feedbackNotificationId(number: String): Int = stableId(number, FEEDBACK_ID_SALT)

    private var lastNotifTime = 0L
    private var blockedSinceLastNotif = 0
    private val lock = Any()
    private val repeatedUrgentNoticeGate = OneShotNoticeGate()

    /**
     * Safely post a notification, honoring the API 33+ POST_NOTIFICATIONS runtime
     * permission. If the permission is not granted we silently drop the notification
     * instead of letting `nm.notify()` raise a SecurityException under StrictMode or
     * crash on devices that enforce the permission strictly. Channel creation does
     * not need a permission, only the actual post.
     */
    private fun safeNotify(
        context: Context,
        id: Int,
        builder: NotificationCompat.Builder,
    ) {
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
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RATING, context.getString(R.string.notif_channel_rating), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_rating_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notif_channel_status_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PHISHING, context.getString(R.string.notif_channel_phishing), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_phishing_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALLOWED, context.getString(R.string.notif_channel_allowed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_allowed_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, context.getString(R.string.notif_channel_digest), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_digest_desc)
            },
        )
    }

    fun notifyBlocked(
        context: Context,
        number: String,
        reason: String,
        isCall: Boolean,
        smsBody: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val nid = stableId(number, if (isCall) 1 else 2)
        val smsIndicators = reportableSmsIndicators(isCall, smsBody)

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

        val openIntent =
            PendingIntent.getActivity(
                context,
                stableId(number, 40),
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_number", number)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val blockIntent =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 10),
                Intent(context, SpamActionReceiver::class.java).apply {
                    action = ACTION_BLOCK
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_NOTIF_ID, nid)
                    putReportExtras(isCall, smsIndicators)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val reportIntent =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 20),
                Intent(context, SpamActionReceiver::class.java).apply {
                    action = ACTION_REPORT
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_NOTIF_ID, nid)
                    putReportExtras(isCall, smsIndicators)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_BLOCKED)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle(context.getString(R.string.notif_blocked_title, typeText))
                .setContentText(context.getString(R.string.notif_blocked_text, PhoneFormatter.formatIsolated(number), reason))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setGroup(GROUP_BLOCKED)
                // Android 16 auto-groups same-app notifications and cooldown-mutes
                // rapid children. Alert via the summary only so a burst of blocks
                // stays a single coherent, non-muted group.
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_block_forever), blockIntent)
                .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.notif_action_report), reportIntent)
        builder.addSmsSafeAction(context, number, nid, isCall)

        safeNotify(context, nid, builder)
        updateSummary(context)
    }

    private fun NotificationCompat.Builder.addSmsSafeAction(
        context: Context,
        number: String,
        notificationId: Int,
        isCall: Boolean,
    ) {
        if (isCall) return
        val safeIntent =
            PendingIntent.getBroadcast(
                context,
                stableId(number, SMS_SAFE_ACTION_SALT),
                Intent(context, SpamActionReceiver::class.java).apply {
                    action = ACTION_SAFE
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_NOTIF_ID, notificationId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        addAction(
            android.R.drawable.ic_menu_save,
            context.getString(R.string.notif_repeated_urgent_action_safe),
            safeIntent,
        )
    }

    private fun Intent.putReportExtras(
        isCall: Boolean,
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators,
    ) {
        putExtra(EXTRA_IS_CALL, isCall)
        if (!smsIndicators.isEmpty()) {
            putStringArrayListExtra(EXTRA_SMS_DOMAINS, ArrayList(smsIndicators.domains))
            putStringArrayListExtra(EXTRA_SMS_URL_INDICATORS, ArrayList(smsIndicators.urlIndicators))
        }
    }

    /**
     * Build and post (or cancel) the group-summary notification. The count
     * read and the eventual `notify()` are NOT serialized — `safeNotify`
     * itself talks to the system service and we don't want to hold the
     * `lock` across a binder call. Worst case: two concurrent callers post
     * a summary with off-by-one counts; both target the same notification
     * ID so the latest one wins. We avoided the previous race where a
     * stale `count` from `synchronized(lock) { blockedSinceLastNotif }`
     * could be used to post a summary AFTER another thread had reset the
     * counter — by snapshotting the count and short-circuiting on zero
     * inside the same call.
     */
    private fun updateSummary(context: Context) {
        val count = synchronized(lock) { blockedSinceLastNotif }
        if (count <= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                nm.cancel(SUMMARY_ID)
            } catch (_: SecurityException) {
            }
            return
        }
        val summaryText = context.resources.getQuantityString(R.plurals.notif_summary_text_recent, count, count)
        val summary =
            NotificationCompat
                .Builder(context, CHANNEL_BLOCKED)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(summaryText)
                .setGroup(GROUP_BLOCKED)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setAutoCancel(true)
        safeNotify(context, SUMMARY_ID, summary)
    }

    fun notifyPhishingUrl(
        context: Context,
        sender: String,
        threats: String,
    ) {
        val nid = stableId(sender, 50)

        val openIntent =
            PendingIntent.getActivity(
                context,
                stableId(sender, 51),
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_number", sender)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_PHISHING)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notif_phishing_title))
                .setContentText(context.getString(R.string.notif_phishing_text, PhoneFormatter.formatIsolated(sender), threats))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(context.getString(R.string.notif_phishing_big_text, PhoneFormatter.formatIsolated(sender), threats)),
                ).setContentIntent(openIntent)
                .setAutoCancel(true)

        safeNotify(context, nid, builder)
    }

    fun notifyAfterCall(
        context: Context,
        number: String,
    ) {
        // Don't show for very short numbers (short codes)
        if (filterAsciiDigits(number).length < 7) return

        // Create intents for "Spam" and "Not Spam" actions. Use distinct
        // [stableId] salts (instead of `number.hashCode()` / `+ 1`) so the
        // pending-intent request codes can't collide with the IDs the
        // block notification uses for its own intents — `hashCode()` and
        // `hashCode() + 1` previously had a real chance of clashing with
        // the block path's `stableId(number, 10/20/40)` outputs.
        val spamIntent =
            Intent(context, SpamActionReceiver::class.java).apply {
                action = "com.sysadmindoc.callshield.FEEDBACK_SPAM"
                putExtra("number", number)
            }
        val notSpamIntent =
            Intent(context, SpamActionReceiver::class.java).apply {
                action = "com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM"
                putExtra("number", number)
            }

        val spamPending =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 60),
                spamIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notSpamPending =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 61),
                notSpamIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val formatted = PhoneFormatter.formatIsolated(number)

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_RATING)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(context.getString(R.string.feedback_title))
                .setContentText(context.getString(R.string.feedback_text, formatted))
                .addAction(0, context.getString(R.string.feedback_spam), spamPending)
                .addAction(0, context.getString(R.string.feedback_not_spam), notSpamPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

        // Distinct notification ID via [feedbackNotificationId] (salt 62) —
        // avoids the previous `number.hashCode() + 10_000` scheme, which
        // produced IDs that could collide with blocked-notification IDs from a
        // different number whose `hashCode()` differed by ~10 000. The action
        // receiver cancels this same ID when the user taps Spam/Not-Spam.
        safeNotify(context, feedbackNotificationId(number), builder)
    }

    fun notifyRepeatedUrgentAllowed(
        context: Context,
        number: String,
    ) {
        val digits = filterAsciiDigitsLast(number, 10)
        if (digits.length < 7) return
        if (!repeatedUrgentNoticeGate.shouldShow("repeated_urgent:$digits")) return

        val nid = stableId(number, 70)
        val formatted = PhoneFormatter.formatIsolated(number)
        val openIntent =
            PendingIntent.getActivity(
                context,
                stableId(number, 71),
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_number", number)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val blockIntent =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 72),
                Intent(context, SpamActionReceiver::class.java).apply {
                    action = ACTION_BLOCK
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_NOTIF_ID, nid)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val safeIntent =
            PendingIntent.getBroadcast(
                context,
                stableId(number, 73),
                Intent(context, SpamActionReceiver::class.java).apply {
                    action = ACTION_SAFE
                    putExtra(EXTRA_NUMBER, number)
                    putExtra(EXTRA_NOTIF_ID, nid)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ALLOWED)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(context.getString(R.string.notif_repeated_urgent_title))
                .setContentText(context.getString(R.string.notif_repeated_urgent_text, formatted))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.notif_repeated_urgent_big_text, formatted),
                    ),
                ).setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_block_forever), blockIntent)
                .addAction(android.R.drawable.ic_menu_save, context.getString(R.string.notif_repeated_urgent_action_safe), safeIntent)

        safeNotify(context, nid, builder)
    }

    fun showPersistentStatus(
        context: Context,
        active: Boolean,
    ) {
        if (!active) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(STATUS_ID)
            return
        }
        val openIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(context.getString(R.string.notif_status_title))
                .setContentText(context.getString(R.string.notif_status_text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setSilent(true)
        safeNotify(context, STATUS_ID, builder)
    }
}
