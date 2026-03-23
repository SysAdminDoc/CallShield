package com.sysadmindoc.callshield.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SpamActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)

        if (notifId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        val repo = SpamRepository.getInstance(context)

        when (intent.action) {
            NotificationHelper.ACTION_BLOCK -> {
                CoroutineScope(Dispatchers.IO).launch {
                    repo.blockNumber(number, "spam", "Blocked from notification")
                    // Also contribute to community database anonymously
                    CommunityContributor.contribute(number, "spam")
                }
            }
            NotificationHelper.ACTION_REPORT -> {
                // Anonymous one-tap contribution — no browser, no GitHub account needed
                CoroutineScope(Dispatchers.IO).launch {
                    CommunityContributor.contribute(number, "spam")
                }
            }
            NotificationHelper.ACTION_SAFE -> {
                // Dismiss only
            }
        }
    }
}
