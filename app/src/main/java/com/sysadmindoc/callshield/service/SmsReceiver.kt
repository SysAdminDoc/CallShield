package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import com.sysadmindoc.callshield.data.MessageCapabilityDetector
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerPriority
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.di.ApplicationScope
import com.sysadmindoc.callshield.domain.usecase.CheckSpamSmsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var repo: SpamRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    companion object {
        /** Hard cap on reassembled multipart body length (16 KB). */
        internal const val MAX_REASSEMBLED_BODY = 16_384

        /**
         * Reassemble a multipart SMS body with a hard length cap. A legitimate
         * SMS spans at most ~40 segments (GSM/UCS-2 limit ~6 400 chars); a
         * malformed or hostile delivery can claim hundreds of parts. Capping at
         * [MAX_REASSEMBLED_BODY] matches the deep-analysis cap in
         * [com.sysadmindoc.callshield.data.SmsContentAnalyzer] so we never
         * shovel data into a regex engine we won't read. Pure so it is
         * unit-testable without the Telephony stack.
         */
        internal fun reassembleBody(parts: List<String?>): String =
            buildString {
                for (part in parts) {
                    if (part == null) continue
                    if (length >= MAX_REASSEMBLED_BODY) break
                    val remaining = MAX_REASSEMBLED_BODY - length
                    append(if (part.length <= remaining) part else part.substring(0, remaining))
                }
            }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val checkSpamSms = CheckSpamSmsUseCase(SpamRepositoryAdapter(repo))
        val pendingResult = goAsync()

        // Keep work off the main thread without spinning a raw thread per SMS.
        // goAsync() keeps the broadcast alive until pendingResult.finish() is
        // called. We use appScope rather than a short-lived one so local URL
        // checks and the optional URLhaus lookup can continue afterward.
        applicationScope.launch {
            var sender = ""
            var body = ""
            var stripUrlhausQuery = true
            var remoteUrlLookupEnabled = false
            try {
                val prefs = repo.readPrefsSnapshot()
                stripUrlhausQuery = prefs[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true
                remoteUrlLookupEnabled = prefs[SpamRepository.KEY_URLHAUS_REMOTE_LOOKUP] ?: false
                val blockSmsEnabled = prefs[SpamRepository.KEY_BLOCK_SMS] ?: true

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val observedAtMillis = System.currentTimeMillis()
                val latestMessageTimestamp = messages?.maxOfOrNull { it.timestampMillis } ?: 0L
                repo.recordMessageCapability(
                    MessageCapabilityDetector.classifySmsBroadcast(
                        apiLevel = Build.VERSION.SDK_INT,
                        messagesDelivered = !messages.isNullOrEmpty(),
                        senderPresent = messages?.firstOrNull()?.originatingAddress?.isNotBlank() == true,
                        bodyPresent = messages?.any { !it.messageBody.isNullOrBlank() } == true,
                        latencyMillis =
                            MessageCapabilityDetector.latencyMillis(
                                sourceTimestampMillis = latestMessageTimestamp,
                                observedAtMillis = observedAtMillis,
                            ),
                        observedAtMillis = observedAtMillis,
                    ),
                )
                if (messages.isNullOrEmpty()) {
                    return@launch
                }

                sender = messages[0].originatingAddress?.takeIf { it.isNotBlank() } ?: return@launch
                body = reassembleBody(messages.map { it.messageBody })

                // Spam classification + block logging is gated behind the
                // Block-SMS toggle. Local phishing-URL checks below run
                // regardless; remote URLhaus checks require their own opt-in.
                if (blockSmsEnabled) {
                    val result = checkSpamSms(sender, body, prefsSnapshot = prefs)
                    if (result.isSpam) {
                        repo.logBlockedCall(
                            number = sender,
                            isCall = false,
                            smsBody = body,
                            matchReason = result.matchSource,
                            confidence = result.confidence,
                            ruleId = result.ruleId,
                            pipelineDiagnostic = result.screeningDiagnostics?.toWireValue(),
                        )
                    } else if (result.screeningDiagnostics?.hasIssues == true) {
                        repo.logScreeningDiagnostic(
                            number = sender,
                            isCall = false,
                            smsBody = body,
                            pipelineDiagnostic = result.screeningDiagnostics.toWireValue().orEmpty(),
                        )
                    } else if (CheckerPriority.isSafetyFloor(result.matchSource)) {
                        repo.logScreeningExemption(
                            number = sender,
                            smsBody = body,
                            matchReason = result.matchSource,
                            type = result.type,
                            confidence = result.confidence,
                            ruleId = result.ruleId,
                        )
                    }
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

            // Background local domain check plus optional domain-only threat
            // feed lookup. This runs after the broadcast decision so it never
            // adds latency to SMS delivery.
            if (body.isNotEmpty()) {
                try {
                    val maliciousUrls =
                        UrlSafetyChecker.checkSmsBody(
                            body,
                            stripQuery = stripUrlhausQuery,
                            allowRemoteLookup = remoteUrlLookupEnabled,
                        )
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
