package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.domain.usecase.CheckSpamSmsUseCase
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        /** Hard cap on reassembled multipart body length (16 KB). */
        internal const val MAX_REASSEMBLED_BODY = 16_384
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val repo = SpamRepository.getInstance(appContext)
        val checkSpamSms = CheckSpamSmsUseCase(SpamRepositoryAdapter(repo))
        val pendingResult = goAsync()

        // Keep work off the main thread without spinning a raw thread per SMS.
        // goAsync() keeps the broadcast alive until pendingResult.finish() is
        // called. We use appScope rather than a short-lived one so the URLhaus
        // phishing check can continue after we've finished with the broadcast.
        CallShieldApp.appScope.launch {
            var sender = ""
            var body = ""
            var stripUrlhausQuery = true
            try {
                val prefs = repo.readPrefsSnapshot()
                stripUrlhausQuery = prefs[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true
                if (!(prefs[SpamRepository.KEY_BLOCK_SMS] ?: true)) {
                    return@launch
                }

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    return@launch
                }

                sender = messages[0].originatingAddress ?: return@launch
                // Cap reassembled multipart body length. A legitimate SMS
                // spans at most ~40 segments (GSM/UCS-2 limit ~ 6 400 chars);
                // a malformed or hostile delivery can claim hundreds of
                // parts. Capping at 16 KB matches the deep-analysis cap in
                // [SmsContentAnalyzer] so we don't shovel data into a
                // regex engine we won't read.
                body = buildString {
                    for (msg in messages) {
                        val part = msg.messageBody ?: continue
                        if (length >= MAX_REASSEMBLED_BODY) break
                        val remaining = MAX_REASSEMBLED_BODY - length
                        append(if (part.length <= remaining) part else part.substring(0, remaining))
                    }
                }

                val result = checkSpamSms(sender, body, prefsSnapshot = prefs)
                if (result.isSpam) {
                    repo.logBlockedCall(
                        number = sender,
                        isCall = false,
                        smsBody = body,
                        matchReason = result.matchSource,
                        confidence = result.confidence
                    )
                    // NOTE: we deliberately do NOT call abortBroadcast() here.
                    //
                    // abortBroadcast() is only meaningful on ordered broadcasts
                    // delivered to the default SMS app (SMS_DELIVER_ACTION).
                    // CallShield listens on SMS_RECEIVED_ACTION, which on
                    // modern Android (API 26+) is either unordered or the
                    // abort is a no-op for non-default SMS apps depending on
                    // the OEM. The message will still land in the user's SMS
                    // inbox — the only way to actually suppress delivery is
                    // for CallShield to become the default SMS app, which
                    // would require reimplementing the entire messaging
                    // surface. We log the block so the user can see it in
                    // the CallShield log + notification, and we leave the
                    // inbox alone.
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
                    val maliciousUrls = UrlSafetyChecker.checkSmsBody(body, stripQuery = stripUrlhausQuery)
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
