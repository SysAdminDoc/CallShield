package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val repo = SpamRepository.getInstance(appContext)
        val pendingResult = goAsync()

        // Keep work off the main thread without spinning a raw thread per SMS.
        // goAsync() keeps the broadcast alive until we finish the blocking decision.
        CallShieldApp.appScope.launch {
            var capturedSender = ""
            var capturedBody = ""
            try {
                if (!repo.blockSmsEnabled.first()) {
                    return@launch
                }

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    return@launch
                }

                val sender = messages[0].originatingAddress ?: return@launch
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
                    abortBroadcast()
                }
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
                CallShieldApp.appScope.launch {
                    try {
                        val maliciousUrls = UrlSafetyChecker.checkSmsBody(body)
                        if (maliciousUrls.isNotEmpty()) {
                            val threats = maliciousUrls.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                            NotificationHelper.notifyPhishingUrl(appContext, sender, threats)
                        }
                    } catch (_: Exception) {
                        // URL check is best-effort — don't crash
                    }
                }
            }
        }
    }
}
