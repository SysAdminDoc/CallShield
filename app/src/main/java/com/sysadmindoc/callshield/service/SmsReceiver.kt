package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val repo = SpamRepository.getInstance(appContext)
        val pendingResult = goAsync()

        // Keep work off the main thread without spinning a raw thread per SMS.
        // goAsync() keeps the broadcast alive until pendingResult.finish() is
        // called. We use appScope rather than a short-lived one so the URLhaus
        // phishing check can continue after we've finished with the broadcast.
        CallShieldApp.appScope.launch {
            var sender = ""
            var body = ""
            try {
                val prefs = repo.readPrefsSnapshot()
                if (!(prefs[SpamRepository.KEY_BLOCK_SMS] ?: true)) {
                    return@launch
                }

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    return@launch
                }

                sender = messages[0].originatingAddress ?: return@launch
                body = messages.joinToString("") { it.messageBody ?: "" }

                val result = repo.isSpamSms(sender, body, prefsSnapshot = prefs)
                if (result.isSpam) {
                    repo.logBlockedCall(
                        number = sender,
                        isCall = false,
                        smsBody = body,
                        matchReason = result.matchSource,
                        confidence = result.confidence
                    )
                    // NOTE: abortBroadcast() only suppresses delivery to lower-
                    // priority receivers on ordered broadcasts. Since CallShield
                    // is not the default SMS app, the message still lands in the
                    // user's SMS inbox — this call just ensures we're first in
                    // line when the broadcast is ordered (API-dependent) and
                    // records the block in our own log.
                    abortBroadcast()
                }
            } catch (_: Exception) {
                // Don't crash the receiver — allow SMS through on error
            } finally {
                pendingResult.finish()
            }

            // Background URLhaus phishing URL check — runs after the broadcast
            // decision so it never adds latency to SMS delivery. Fires a
            // warning notification if the message contains a URL listed in
            // URLhaus. Wrapped in its own try/catch so a network hiccup can't
            // propagate out of the receiver.
            if (body.isNotEmpty()) {
                try {
                    val maliciousUrls = UrlSafetyChecker.checkSmsBody(body)
                    if (maliciousUrls.isNotEmpty()) {
                        val threats = maliciousUrls.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                        NotificationHelper.notifyPhishingUrl(appContext, sender, threats)
                    }
                } catch (_: Exception) {
                    // URL check is best-effort
                }
            }
        }
    }
}
