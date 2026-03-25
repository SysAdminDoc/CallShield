package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val repo = SpamRepository.getInstance(context)
        val pendingResult = goAsync()

        // Use runBlocking to ensure spam check completes before broadcast finishes.
        // goAsync() gives us ~10s, which is plenty for local DB + heuristic checks.
        Thread {
            var capturedSender = ""
            var capturedBody = ""
            try {
                val shouldBlock = runBlocking {
                    if (!repo.blockSmsEnabled.first()) return@runBlocking false

                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (messages.isNullOrEmpty()) return@runBlocking false

                    val sender = messages[0].originatingAddress ?: return@runBlocking false
                    val body = messages.joinToString("") { it.messageBody ?: "" }
                    capturedSender = sender
                    capturedBody = body

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

            // Background URLhaus phishing URL check — runs after broadcast decision
            // so it never adds latency to SMS delivery. Fires a warning notification
            // if the message contains a URL listed in the URLhaus malware/phishing DB.
            if (capturedBody.isNotEmpty()) {
                val body = capturedBody
                val sender = capturedSender
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val maliciousUrls = UrlSafetyChecker.checkSmsBody(body)
                        if (maliciousUrls.isNotEmpty()) {
                            val threats = maliciousUrls.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                            NotificationHelper.notifyPhishingUrl(context, sender, threats)
                        }
                    } catch (_: Exception) {
                        // URL check is best-effort — don't crash
                    }
                }
            }
        }.start()
    }
}
