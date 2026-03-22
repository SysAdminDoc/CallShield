package com.sysadmindoc.callshield.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification quick actions: Block, Report, Safe.
 * Features 5 & 11: Notification actions + community reporting.
 */
class SpamActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER) ?: return
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)

        // Dismiss the notification
        if (notifId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        val repo = SpamRepository.getInstance(context)

        when (intent.action) {
            NotificationHelper.ACTION_BLOCK -> {
                CoroutineScope(Dispatchers.IO).launch {
                    repo.blockNumber(number, "spam", "Blocked from notification")
                }
            }
            NotificationHelper.ACTION_REPORT -> {
                // Open GitHub Issue URL pre-filled with the number
                val title = Uri.encode("[SPAM] $number")
                val body = Uri.encode("## Phone Number\n$number\n\n## Type\nReported from CallShield app\n\n## Description\nUser-reported spam number")
                val url = "https://github.com/SysAdminDoc/CallShield/issues/new?title=$title&body=$body&labels=spam-report"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
            NotificationHelper.ACTION_SAFE -> {
                // No action needed — just dismiss
            }
        }
    }
}
