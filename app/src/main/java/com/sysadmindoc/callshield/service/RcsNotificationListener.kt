package com.sysadmindoc.callshield.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.NotificationScreeningCategory
import com.sysadmindoc.callshield.data.NotificationScreeningSource
import com.sysadmindoc.callshield.data.NotificationScreeningSources
import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
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
 * Screens notifications from the explicitly enabled messaging and email
 * sources. Numeric SMS/RCS senders use CallShield's SMS rules; private
 * messenger and email sources receive non-destructive content warnings.
 *
 * IMPORTANT LIMITATIONS:
 *  - Cannot prevent message delivery to the source app
 *  - Message content is read from the notification text, which may be truncated
 *  - Requires user to grant Notification Access in Settings → Apps
 *
 * WHAT IT DOES:
 *  - If a numeric SMS/RCS sender matches blocking rules → cancel the notification
 *  - If private-messenger/email content matches → keep the original and warn
 *  - If SMS blocking is disabled → skips spam classification/removal while
 *    retaining malicious-URL warnings
 *  - Fires URLhaus background check for URLs in RCS messages
 *  - Logs blocked RCS to the CallShield blocked log (visible in Blocked tab)
 *
 * Google and Samsung Messages are the only defaults. Every other catalog
 * source is opt-in, and unselected sources are rejected before extras are read.
 */
class RcsNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // A3 toggle state, observed off the hot path so onNotificationPosted
    // can read it without suspending. `true` until the DataStore observer
    // first delivers — matches the feature's "on by default" contract and
    // avoids a race where the first notification after boot arrives before
    // the collector fires.
    @Volatile private var pushAlertEnabled: Boolean = true

    @Volatile
    private var enabledScreeningPackages: Set<String> = NotificationScreeningSources.defaultEnabledPackages

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
        scope.launch {
            repo.notificationScreeningPackages.collectLatest { enabled ->
                enabledScreeningPackages = enabled
            }
        }
    }

    @Suppress("ReturnCount")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return // skip ongoing (media controls, etc.)

        val screeningSource = NotificationScreeningSources.sourceFor(sbn.packageName)
        if (screeningSource != null &&
            !NotificationScreeningSources.shouldReadPackage(sbn.packageName, enabledScreeningPackages)
        ) {
            return
        }

        // A3: Feed the push-alert registry for any allowlisted source app
        // that the user hasn't opted out of. Master toggle and per-package
        // opt-out are both checked here — if either is off, the
        // notification content never enters the buffer.
        if (pushAlertEnabled && PushAlertRegistry.isAllowedSource(sbn.packageName)) {
            captureAlert(sbn)
        }

        if (screeningSource == null) return

        scope.launch {
            try {
                processNotification(sbn, screeningSource)
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

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun processNotification(
        sbn: StatusBarNotification,
        source: NotificationScreeningSource,
    ) {
        val repo = SpamRepository.getInstance(applicationContext)
        val checkSpamSms = CheckSpamSmsUseCase(SpamRepositoryAdapter(repo))
        val prefs = repo.readPrefsSnapshot()
        val stripUrlhausQuery = prefs[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true

        // Notification screening owns content access; the Block SMS toggle
        // controls only spam classification/removal. URL warnings intentionally
        // remain active, matching the direct-SMS receiver's safety contract.
        if (!isNotificationScreeningEnabled(prefs)) return
        val spamBlockingEnabled = isSpamBlockingEnabled(prefs)

        val extras = sbn.notification.extras ?: return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (sender.isEmpty()) return

        val senderDigits = filterAsciiDigits(sender)
        // E2EE graceful degradation: when the body is empty or an encrypted
        // placeholder, fall back to sender-number-only analysis (database +
        // heuristics, no content rules). GSMA UP 3.0/4.0 MLS encryption
        // will progressively make RCS notification bodies opaque.
        val effectiveBody = body.takeIf { it.isNotBlank() && !isEncryptedPlaceholder(it) }
        val result =
            senderDigits
                .takeIf { spamBlockingEnabled && it.length >= 7 }
                ?.let { number -> checkSpamSms(number, effectiveBody.orEmpty(), prefsSnapshot = prefs) }
        val contentVerdict =
            effectiveBody?.takeIf { spamBlockingEnabled && result == null }?.let { bodyText ->
                contentVerdict(
                    body = bodyText,
                    enabled = prefs[SpamRepository.KEY_SMS_CONTENT] ?: true,
                    aggressive = prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false,
                )
            }
        val isSpam = result?.isSpam == true || contentVerdict?.isSpam == true
        val confidence = maxOf(result?.confidence ?: 0, contentVerdict?.confidence ?: 0)
        val reason =
            result
                ?.takeIf { it.isSpam }
                ?.matchSource
                ?: contentVerdict?.reason.orEmpty()

        if (isSpam && source.category == NotificationScreeningCategory.RCS && result?.isSpam == true) {
            cancelNotification(sbn.key)
            repo.logBlockedCall(
                number = senderDigits,
                isCall = false,
                smsBody = effectiveBody,
                matchReason = "rcs_${result.matchSource}",
                confidence = confidence,
            )
        } else if (isSpam) {
            NotificationHelper.notifyScreenedMessage(
                context = applicationContext,
                sourceName = source.stableName,
                sender = sender,
                confidence = confidence,
                reason = reason,
            )
        } else if (effectiveBody != null) {
            launchUrlSafetyWarning(effectiveBody, sender, source, stripUrlhausQuery)
        }
    }

    private fun launchUrlSafetyWarning(
        body: String,
        sender: String,
        source: NotificationScreeningSource,
        stripQuery: Boolean,
    ) {
        if (body.isBlank()) return
        scope.launch {
            val malicious = UrlSafetyChecker.checkSmsBody(body, stripQuery = stripQuery)
            if (malicious.isNotEmpty()) {
                val threats = malicious.joinToString(", ") { it.threat.ifEmpty { "malware" } }
                if (source.category == NotificationScreeningCategory.RCS && filterAsciiDigits(sender).length >= 7) {
                    NotificationHelper.notifyPhishingUrl(applicationContext, sender, threats)
                } else {
                    NotificationHelper.notifyScreenedMessage(
                        context = applicationContext,
                        sourceName = source.stableName,
                        sender = sender,
                        confidence = 100,
                        reason = threats,
                    )
                }
            }
        }
    }

    /**
     * The OS unbinds this listener on low memory, app update, or an OEM
     * process kill. Without an explicit rebind the service stays dead —
     * all RCS filtering and push-alert capture silently stop until the
     * next reboot or a manual Settings → Notification Access toggle. Ask
     * the framework to rebind so protection self-heals.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, RcsNotificationListener::class.java))
        } catch (_: Exception) {
            // requestRebind can throw if notification access was revoked;
            // nothing more we can do from here — user must re-grant.
        }
    }

    /**
     * After a rebind the framework may deliver an empty active-notification
     * set before the messaging app re-posts; nothing to seed, so this is a
     * defensive no-op that documents the handled edge.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        // getActiveNotifications() can legitimately be empty post-rebind.
        // No priming needed: the registry rebuilds from live onNotificationPosted.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal fun isNotificationScreeningEnabled(
            prefs: Preferences,
        ): Boolean = prefs[SpamRepository.KEY_RCS_FILTER] ?: true

        internal fun isSpamBlockingEnabled(
            prefs: Preferences,
        ): Boolean = prefs[SpamRepository.KEY_BLOCK_SMS] ?: true

        internal data class ContentVerdict(
            val isSpam: Boolean,
            val confidence: Int,
            val reason: String,
        )

        internal fun contentVerdict(
            body: String,
            enabled: Boolean,
            aggressive: Boolean,
        ): ContentVerdict {
            if (!enabled) return ContentVerdict(false, 0, "")
            val analysis = SmsContentAnalyzer.analyze(body)
            val threshold = if (aggressive) AGGRESSIVE_CONTENT_THRESHOLD else DEFAULT_CONTENT_THRESHOLD
            return ContentVerdict(
                isSpam = analysis.score >= threshold,
                confidence = analysis.score,
                reason = analysis.reasons.joinToString(", ") { it.replace('_', ' ') },
            )
        }

        /** Longest body still treated as a possible encryption placeholder. */
        private const val MAX_PLACEHOLDER_LEN = 48
        private const val AGGRESSIVE_CONTENT_THRESHOLD = 25
        private const val DEFAULT_CONTENT_THRESHOLD = 50

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
