package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val repo = SpamRepository.getInstance(context)
        val pendingResult = goAsync()

        // Use runBlocking to ensure spam check completes before broadcast finishes.
        // goAsync() gives us ~10s, which is plenty for local DB + heuristic checks.
        Thread {
            try {
                val shouldBlock = runBlocking {
                    if (!repo.blockSmsEnabled.first()) return@runBlocking false

                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (messages.isNullOrEmpty()) return@runBlocking false

                    val sender = messages[0].originatingAddress ?: return@runBlocking false
                    val body = messages.joinToString("") { it.messageBody ?: "" }

                    val result = repo.isSpamSms(sender, body)
                    if (result.isSpam) {
                        repo.logBlockedCall(
                            number = sender,
                            isCall = false,
                            smsBody = body,
                            matchReason = result.matchSource,
                            confidence = result.confidence
                        )
                        true
                    } else false
                }
                if (shouldBlock) abortBroadcast()
            } catch (_: Exception) {
                // Don't crash the receiver — allow SMS through on error
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
