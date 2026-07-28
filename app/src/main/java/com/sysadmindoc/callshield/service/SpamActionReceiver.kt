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
                    notificationManager.cancel(NotificationHelper.feedbackNotificationId(number))
                    Toast.makeText(appContext, appContext.getString(R.string.feedback_blocked), Toast.LENGTH_SHORT).show()
                    suspend {
                        repo.blockNumber(number, "spam", "Blocked from after-call feedback")
                        CommunityContributor.contribute(repo.normalizeNumber(number), "spam")
                    }
                }

                ACTION_FEEDBACK_NOT_SPAM -> {
                    val number = intent.getStringExtra(EXTRA_FEEDBACK_NUMBER) ?: return
                    notificationManager.cancel(NotificationHelper.feedbackNotificationId(number))
                    Toast.makeText(appContext, appContext.getString(R.string.feedback_whitelisted), Toast.LENGTH_SHORT).show()
                    suspend {
                        repo.addToWhitelist(number, "Marked safe from after-call feedback")
                        CommunityContributor.reportNotSpam(repo.normalizeNumber(number))
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
                        repo.blockNumber(number, reportType, "Blocked from notification")
                        CommunityContributor.contribute(repo.normalizeNumber(number), reportType, smsIndicators)
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
                        CommunityContributor.contribute(repo.normalizeNumber(number), reportType, smsIndicators)
                    }
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
                        repo.addToWhitelist(number, "Reported as not spam from notification")
                        CommunityContributor.reportNotSpam(repo.normalizeNumber(number))
                    }
                }

                else -> {
                    null
                }
            } ?: return

        // Previously this created a fresh CoroutineScope(SupervisorJob+IO) per
        // broadcast, which was never cancelled and leaked a Job on every
        // action. Reuse the process-wide appScope — the work must outlive
        // onReceive anyway (goAsync covers the broadcast lifetime, not the
        // suspend body).
        val pendingResult = goAsync()
        CallShieldApp.appScope.launch {
            try {
                work.invoke()
            } catch (e: Exception) {
                // The confirmation toast already fired for responsiveness; a
                // failed DB write must not stay silent (and an uncaught throw
                // here would take down the process via appScope).
                Log.w("SpamActionReceiver", "Notification action failed", e)
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(appContext, appContext.getString(R.string.notification_action_failed), Toast.LENGTH_SHORT)
                        .show()
                }
            } finally {
                pendingResult.finish()
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
    }
}
