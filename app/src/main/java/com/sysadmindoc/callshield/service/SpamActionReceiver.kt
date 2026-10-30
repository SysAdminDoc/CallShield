package com.sysadmindoc.callshield.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpamActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val repo = SpamRepository.getInstance(appContext)

        val work =
            when (intent.action) {
                ACTION_FEEDBACK_SPAM -> {
                    val number = intent.getStringExtra(EXTRA_FEEDBACK_NUMBER) ?: return
                    spamFeedback(appContext, number)
                }

                ACTION_FEEDBACK_NOT_SPAM -> {
                    val number = intent.getStringExtra(EXTRA_FEEDBACK_NUMBER) ?: return
                    notificationManager.cancel(NotificationHelper.feedbackNotificationId(number))
                    Toast.makeText(appContext, appContext.getString(R.string.feedback_whitelisted), Toast.LENGTH_SHORT).show()
                    suspend {
                        repo.addToWhitelist(number, appContext.getString(R.string.desc_marked_safe_from_after_call_feedback))
                        CommunityContributor.reportNotSpam(appContext, repo.normalizeNumber(number))
                    }
                }

                NotificationHelper.ACTION_BLOCK -> {
                    val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                    val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                    val reportType = intent.reportType()
                    val smsIndicators = intent.smsReportIndicators()
                    if (notifId >= 0) {
                        notificationManager.cancel(notifId)
                    }
                    suspend {
                        repo.blockNumber(number, reportType, appContext.getString(R.string.desc_blocked_from_notification))
                        CommunityContributor.contribute(appContext, repo.normalizeNumber(number), reportType, smsIndicators)
                    }
                }

                NotificationHelper.ACTION_NOT_SPAM -> {
                    intent.getStringExtra(NotificationHelper.EXTRA_NUMBER)?.let { number ->
                        allowFromAlert(appContext, notificationManager, repo, intent, number)
                    }
                }

                NotificationHelper.ACTION_REPORT -> {
                    val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                    val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                    val reportType = intent.reportType()
                    val smsIndicators = intent.smsReportIndicators()
                    if (notifId >= 0) {
                        notificationManager.cancel(notifId)
                    }
                    suspend {
                        CommunityContributor.contribute(appContext, repo.normalizeNumber(number), reportType, smsIndicators)
                    }
                }

                NotificationHelper.ACTION_END_EXPECTING_CALL -> {
                    suspend { ExpectingCallController.end(appContext, repo) }
                }

                NotificationHelper.ACTION_CLEAR_SUMMARY -> {
                    // Fired by the blocked-summary group's deleteIntent when the
                    // user swipes it away. Pure in-memory reset, no async work —
                    // yield null so the shared `?: return` short-circuits below.
                    NotificationHelper.clearBlockedSummaryCount()
                    null
                }

                NotificationHelper.ACTION_SAFE -> {
                    val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                    val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                    if (notifId >= 0) {
                        notificationManager.cancel(notifId)
                    }
                    suspend {
                        repo.addToWhitelist(number, appContext.getString(R.string.desc_reported_not_spam_from_notification))
                        CommunityContributor.reportNotSpam(appContext, repo.normalizeNumber(number))
                    }
                }

                else -> {
                    null
                }
            } ?: return

        // The work must outlive onReceive (goAsync covers the broadcast
        // lifetime, not the suspend body), so it runs on the process-wide
        // appScope rather than a scope created and leaked per broadcast.
        // goAsync() is null when onReceive is called without a broadcast.
        val pendingResult: PendingResult? = goAsync()
        launchAction(appContext, work) { pendingResult?.finish() }
    }

    /** "Not spam" on a blocked-call alert: let the number ring for a day, and tell the community when its data was wrong. */
    private fun allowFromAlert(
        appContext: Context,
        notificationManager: NotificationManager,
        repo: SpamRepository,
        intent: Intent,
        number: String,
    ): suspend () -> Unit {
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
        val reasonCode = BlockReasonCode.fromStored(intent.getStringExtra(NotificationHelper.EXTRA_REASON_CODE))
        val rowSource = intent.getStringExtra(NotificationHelper.EXTRA_ROW_SOURCE)
        if (notifId >= 0) {
            notificationManager.cancel(notifId)
        }
        return {
            // A permanent block the user set wins over the allow, so the toast
            // waits for the answer instead of promising a ring.
            val allowed =
                repo.temporaryAllowNumber(
                    number,
                    System.currentTimeMillis() + NotificationHelper.NOT_SPAM_ALLOW_MS,
                    appContext.getString(R.string.desc_allowed_from_notification),
                )
            withContext(Dispatchers.Main) {
                val message = if (allowed) R.string.notif_not_spam_allowed else R.string.notif_not_spam_refused
                Toast.makeText(appContext, appContext.getString(message), Toast.LENGTH_SHORT).show()
            }
            if (!allowed) {
                NotificationHelper.notifyNotSpamRefused(appContext, number)
            }
            if (allowed && NotificationHelper.notSpamReachesCommunity(reasonCode, rowSource)) {
                CommunityContributor.reportNotSpam(appContext, repo.normalizeNumber(number))
            }
        }
    }

    private fun Intent.reportType(): String =
        if (getBooleanExtra(NotificationHelper.EXTRA_IS_CALL, true)) {
            "spam"
        } else {
            "sms_spam"
        }

    private fun Intent.smsReportIndicators(): SmsContentAnalyzer.SmsReportIndicators =
        SmsContentAnalyzer.SmsReportIndicators(
            domains = getStringArrayListExtra(NotificationHelper.EXTRA_SMS_DOMAINS).orEmpty(),
            urlIndicators = getStringArrayListExtra(NotificationHelper.EXTRA_SMS_URL_INDICATORS).orEmpty(),
        )

    companion object {
        const val ACTION_FEEDBACK_SPAM = "com.sysadmindoc.callshield.FEEDBACK_SPAM"
        const val ACTION_FEEDBACK_NOT_SPAM = "com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM"
        const val EXTRA_FEEDBACK_NUMBER = "number"
        private const val TAG = "SpamActionReceiver"

        /**
         * The after-call "Spam" feedback: cancels the prompt and confirms at
         * once, and returns the block plus the community report to run off
         * the main thread. Shared with the post-call screen, which calls it
         * directly instead of broadcasting to this receiver: Android 17 QPR2
         * no longer lets a broadcast an app sends to itself raise the
         * sender's priority, and that screen finishes right after the tap.
         * Call on the main thread, which the toast needs.
         */
        fun spamFeedback(
            appContext: Context,
            number: String,
        ): suspend () -> Unit {
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NotificationHelper.feedbackNotificationId(number))
            Toast.makeText(appContext, appContext.getString(R.string.feedback_blocked), Toast.LENGTH_SHORT).show()
            val repo = SpamRepository.getInstance(appContext)
            return {
                repo.blockNumber(number, "spam", appContext.getString(R.string.desc_blocked_from_after_call_feedback))
                CommunityContributor.contribute(appContext, repo.normalizeNumber(number), "spam")
            }
        }

        /**
         * Runs [work] now and ignores what it returns. The confirmation toast
         * already fired for responsiveness, so a failed write is logged and
         * shown rather than left silent, and never thrown into the caller's
         * scope.
         */
        suspend fun runAction(
            appContext: Context,
            work: suspend () -> Any?,
        ) {
            try {
                work()
            } catch (e: Exception) {
                Log.w(TAG, "Notification action failed", e)
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(appContext, appContext.getString(R.string.notification_action_failed), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        /** [runAction] on the process scope, then [onDone] whether it succeeded or not. */
        fun launchAction(
            appContext: Context,
            work: suspend () -> Any?,
            onDone: () -> Unit,
        ) {
            CallShieldApp.appScope.launch {
                try {
                    runAction(appContext, work)
                } finally {
                    onDone()
                }
            }
        }
    }
}
