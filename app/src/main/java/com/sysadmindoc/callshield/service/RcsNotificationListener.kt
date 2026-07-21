package com.sysadmindoc.callshield.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.CheckSpamSmsUseCase
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
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
    private val messagingPackages =
        setOf(
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
        // so the hot-path filter is lock-free. Prune cached alerts for
        // newly-disabled packages BEFORE publishing the new set — calling
        // applyOptOuts ensures a concurrent screening verdict can't
        // consume stale alerts from a just-disabled source.
        scope.launch {
            repo.pushAlertDisabledPackages.collectLatest { disabled ->
                PushAlertRegistry.applyOptOuts(disabled)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return // skip ongoing (media controls, etc.)

        // A3: Feed the push-alert registry for any allowlisted source app
        // that the user hasn't opted out of. Master toggle and per-package
        // opt-out are both checked here — if either is off, the
        // notification content never enters the buffer.
        if (pushAlertEnabled && PushAlertRegistry.isAllowedSource(sbn.packageName)) {
            captureAlert(sbn)
        }

        if (sbn.packageName !in messagingPackages) return

        scope.launch {
            try {
                processNotification(sbn)
            } catch (_: Exception) {
            }
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
            ),
        )
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val repo = SpamRepository.getInstance(applicationContext)
        val checkSpamSms = CheckSpamSmsUseCase(SpamRepositoryAdapter(repo))
        val prefs = repo.readPrefsSnapshot()
        val stripUrlhausQuery = prefs[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true

        // Respect the "Block SMS" and "RCS Filter" toggles
        if (!shouldProcessRcs(prefs)) return

        val extras = sbn.notification.extras ?: return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (sender.isEmpty()) return

        val senderDigits = filterAsciiDigits(sender)
        if (senderDigits.length < 7) {
            launchUrlSafetyWarning(body, sender, stripUrlhausQuery)
            return
        }

        // E2EE graceful degradation: when the body is empty or an encrypted
        // placeholder, fall back to sender-number-only analysis (database +
        // heuristics, no content rules). GSMA UP 3.0/4.0 MLS encryption
        // will progressively make RCS notification bodies opaque.
        val effectiveBody = body.takeIf { it.isNotBlank() && !isEncryptedPlaceholder(it) }
        val result =
            if (effectiveBody != null) {
                checkSpamSms(senderDigits, effectiveBody, prefsSnapshot = prefs)
            } else {
                checkSpamSms(senderDigits, "", prefsSnapshot = prefs)
            }

        if (result.isSpam) {
            cancelNotification(sbn.key)
            repo.logBlockedCall(
                number = senderDigits,
                isCall = false,
                smsBody = effectiveBody,
                matchReason = "rcs_${result.matchSource}",
                confidence = result.confidence,
            )
        } else if (effectiveBody != null) {
            launchUrlSafetyWarning(effectiveBody, senderDigits, stripUrlhausQuery)
        }
    }

    private fun shouldProcessRcs(prefs: Preferences): Boolean =
        (prefs[SpamRepository.KEY_BLOCK_SMS] ?: true) &&
            (prefs[SpamRepository.KEY_RCS_FILTER] ?: true)

    private fun launchUrlSafetyWarning(
        body: String,
        sender: String,
        stripQuery: Boolean,
    ) {
        if (body.isBlank()) return
        scope.launch {
            val malicious = UrlSafetyChecker.checkSmsBody(body, stripQuery = stripQuery)
            if (malicious.isNotEmpty()) {
                val threats = malicious.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                NotificationHelper.notifyPhishingUrl(applicationContext, sender, threats)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Longest body still treated as a possible encryption placeholder. */
        private const val MAX_PLACEHOLDER_LEN = 48

        /**
         * Encryption-keyword roots across the Latin-script locales CallShield
         * targets (en/es/pt/fr/de/it/nl). Placeholder detection is
         * keyword-anchored AND length-bounded so it can't swallow real short
         * spam like "You won! bit.ly/x", which carries a URL/offer rather than
         * an encryption keyword. Non-Latin locales (ar/zh/ru) still need their
         * own strings — tracked as future work.
         */
        private val ENCRYPTION_KEYWORD_ROOTS =
            listOf(
                "encrypt", // en (encrypted / encryption)
                "cifrad", // es / pt (cifrado / cifrada)
                "chiffr", // fr (chiffré / chiffre)
                "verschlüsselt", // de
                "crittograf", // it (crittografato)
                "versleuteld", // nl
            )

        /**
         * True when the RCS notification body is an E2EE placeholder rather
         * than real content. Matching is locale-tolerant: exact known English
         * phrasings, plus any short body built around an encryption keyword.
         * A false positive here is safe — it only downgrades to sender-only
         * analysis (no content rules), never a block.
         */
        internal fun isEncryptedPlaceholder(body: String): Boolean {
            val lower = body.lowercase().trim().trim('"', '.', ' ')
            if (lower.isEmpty()) return true
            if (lower == "encrypted message" ||
                lower == "message encrypted" ||
                lower == "encrypted" ||
                lower.startsWith("this message is encrypted") ||
                lower.startsWith("message is encrypted") ||
                lower.startsWith("end-to-end encrypted")
            ) {
                return true
            }
            return lower.length <= MAX_PLACEHOLDER_LEN &&
                ENCRYPTION_KEYWORD_ROOTS.any { lower.contains(it) }
        }
    }
}
