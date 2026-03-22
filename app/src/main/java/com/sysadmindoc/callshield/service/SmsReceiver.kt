package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val repo = SpamRepository.getInstance(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!repo.blockSmsEnabled.first()) return@launch

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return@launch

                val sender = messages[0].originatingAddress ?: return@launch
                val body = messages.joinToString("") { it.messageBody ?: "" }

                val result = repo.isSpam(sender)
                if (result.isSpam) {
                    // Log the blocked SMS
                    repo.logBlockedCall(
                        number = sender,
                        isCall = false,
                        smsBody = body,
                        matchReason = result.matchSource
                    )
                    // Abort broadcast to prevent SMS from reaching inbox
                    abortBroadcast()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
