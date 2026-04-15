package com.sysadmindoc.callshield.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpamActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val repo = SpamRepository.getInstance(appContext)

        val work = when (intent.action) {
            "com.sysadmindoc.callshield.FEEDBACK_SPAM" -> {
                val number = intent.getStringExtra("number") ?: return
                notificationManager.cancel(number.hashCode() + 10000)
                Toast.makeText(appContext, appContext.getString(R.string.feedback_blocked), Toast.LENGTH_SHORT).show()
                suspend {
                    repo.blockNumber(number, "spam", "Blocked from after-call feedback")
                    CommunityContributor.contribute(number, "spam")
                }
            }

            "com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM" -> {
                val number = intent.getStringExtra("number") ?: return
                notificationManager.cancel(number.hashCode() + 10000)
                Toast.makeText(appContext, appContext.getString(R.string.feedback_whitelisted), Toast.LENGTH_SHORT).show()
                suspend {
                    repo.addToWhitelist(number, "Marked safe from after-call feedback")
                    CommunityContributor.reportNotSpam(number)
                }
            }

            NotificationHelper.ACTION_BLOCK -> {
                val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                if (notifId >= 0) {
                    notificationManager.cancel(notifId)
                }
                suspend {
                    repo.blockNumber(number, "spam", "Blocked from notification")
                    CommunityContributor.contribute(number, "spam")
                }
            }

            NotificationHelper.ACTION_REPORT -> {
                val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                if (notifId >= 0) {
                    notificationManager.cancel(notifId)
                }
                suspend {
                    CommunityContributor.contribute(number, "spam")
                }
            }

            NotificationHelper.ACTION_SAFE -> {
                val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
                val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
                if (notifId >= 0) {
                    notificationManager.cancel(notifId)
                }
                suspend {
                    repo.addToWhitelist(number, "Reported as not spam from notification")
                    CommunityContributor.reportNotSpam(number)
                }
            }

            else -> null
        } ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                work.invoke()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
