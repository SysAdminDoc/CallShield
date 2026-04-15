package com.sysadmindoc.callshield.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * RCS Message Spam Filter — NotificationListenerService
 *
 * Intercepts incoming RCS notifications from Google Messages, Samsung
 * Messages, and other RCS apps. Applies CallShield's SMS rules to RCS
 * messages, which bypass the standard SMS_RECEIVED broadcast.
 *
 * IMPORTANT LIMITATIONS:
 *  - Cannot prevent RCS delivery to the Messages app (only hides the notification)
 *  - Message content is read from the notification text, which may be truncated
 *  - Requires user to grant Notification Access in Settings → Apps
 *
 * WHAT IT DOES:
 *  - If sender is in CallShield blocklist → cancel the notification silently
 *  - If message content matches keyword/heuristic rules → cancel notification
 *  - If SMS blocking is disabled → passes all through
 *  - Fires URLhaus background check for URLs in RCS messages
 *  - Logs blocked RCS to the CallShield blocked log (visible in Blocked tab)
 *
 * SUPPORTED APPS:
 *  - com.google.android.apps.messaging (Google Messages)
 *  - com.samsung.android.messaging (Samsung Messages)
 *  - com.android.mms (AOSP Messages fallback)
 */
class RcsNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Package names of RCS/SMS messaging apps to monitor
    private val MESSAGING_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.microsoft.android.smsorganizer",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSAGING_PACKAGES) return
        if (sbn.isOngoing) return // Skip ongoing notifications (not messages)

        scope.launch {
            try {
                processNotification(sbn)
            } catch (_: Exception) { }
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val repo = SpamRepository.getInstance(applicationContext)

        // Respect the "Block SMS" and "RCS Filter" toggles
        if (!repo.blockSmsEnabled.first()) return
        if (!repo.rcsFilterEnabled.first()) return

        val extras = sbn.notification.extras ?: return

        // Extract sender and body from notification extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (sender.isEmpty() || body.isEmpty()) return

        // Check if sender looks like a phone number (RCS from known contacts shows name)
        // We only process if the sender field is a phone number, not a contact name
        val senderDigits = sender.filter { it.isDigit() }
        if (senderDigits.length < 7) {
            // Sender is a contact name — not anonymous, likely not spam
            // Still run URL check in background though
            if (body.isNotEmpty()) {
                scope.launch {
                    val malicious = UrlSafetyChecker.checkSmsBody(body)
                    if (malicious.isNotEmpty()) {
                        val threats = malicious.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                        NotificationHelper.notifyPhishingUrl(applicationContext, sender, threats)
                    }
                }
            }
            return
        }

        val result = repo.isSpamSms(senderDigits, body)

        if (result.isSpam) {
            // Cancel the notification — user won't see it ring/vibrate
            cancelNotification(sbn.key)

            // Log to CallShield blocked log
            repo.logBlockedCall(
                number = senderDigits,
                isCall = false,
                smsBody = body,
                matchReason = "rcs_${result.matchSource}",
                confidence = result.confidence
            )
        } else {
            // Not blocked — run background URL safety check
            scope.launch {
                val malicious = UrlSafetyChecker.checkSmsBody(body)
                if (malicious.isNotEmpty()) {
                    val threats = malicious.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                    NotificationHelper.notifyPhishingUrl(applicationContext, senderDigits, threats)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
