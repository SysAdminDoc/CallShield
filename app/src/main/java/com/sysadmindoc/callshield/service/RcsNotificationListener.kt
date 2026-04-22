package com.sysadmindoc.callshield.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
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

    // A3 toggle state, observed off the hot path so onNotificationPosted
    // can read it without suspending. `true` until the DataStore observer
    // first delivers — matches the feature's "on by default" contract and
    // avoids a race where the first notification after boot arrives before
    // the collector fires.
    @Volatile private var pushAlertEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        val repo = SpamRepository.getInstance(applicationContext)
        // Also clear the registry when the feature is turned off so stale
        // alerts captured under the previous setting don't inform the
        // pipeline after a toggle flip.
        scope.launch {
            repo.pushAlertEnabled.collectLatest { enabled ->
                pushAlertEnabled = enabled
                if (!enabled) PushAlertRegistry.clear()
            }
        }
        // A3 allowlist editor: push the user's opt-outs into the registry
        // so the hot-path filter is lock-free. When a package flips off,
        // drop any of its cached alerts so the checker doesn't fire on
        // stale content captured under the previous allowlist.
        scope.launch {
            repo.pushAlertDisabledPackages.collectLatest { disabled ->
                val previous = PushAlertRegistry.currentDisabledPackages()
                PushAlertRegistry.setDisabledPackages(disabled)
                val newlyDisabled = disabled - previous
                if (newlyDisabled.isNotEmpty()) {
                    PushAlertRegistry.pruneByPackages(newlyDisabled)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return  // skip ongoing (media controls, etc.)

        // A3: Feed the push-alert registry for any allowlisted source app
        // that the user hasn't opted out of. Master toggle and per-package
        // opt-out are both checked here — if either is off, the
        // notification content never enters the buffer.
        if (pushAlertEnabled && PushAlertRegistry.isAllowedSource(sbn.packageName)) {
            captureAlert(sbn)
        }

        if (sbn.packageName !in MESSAGING_PACKAGES) return

        scope.launch {
            try {
                processNotification(sbn)
            } catch (_: Exception) { }
        }
    }

    /**
     * Extract title + body from a notification and push it into the
     * in-memory registry. Runs synchronously — no coroutine — because
     * [PushAlertRegistry.record] is trivial and we want the alert
     * available before any subsequent call-screening coroutine runs.
     */
    private fun captureAlert(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        PushAlertRegistry.record(
            PushAlertRegistry.Alert(
                packageName = sbn.packageName,
                title = title,
                body = body,
                timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        )
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
