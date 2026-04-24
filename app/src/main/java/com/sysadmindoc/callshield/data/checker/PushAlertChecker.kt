package com.sysadmindoc.callshield.data.checker

import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.SpamRepository

/**
 * Trust-allows an unknown caller when a messaging / delivery / rideshare
 * app has recently posted a notification that mentions the incoming
 * number (or a trust phrase that implies they'll call).
 *
 * Sits above the weaker detection layers (time block, heuristic, ML) but
 * below explicit user blocks (user blocklist, wildcard) so a number the
 * user intentionally blocked stays blocked even when Uber just posted
 * "Your driver is arriving".
 *
 * Inspired by SpamBlocker's `NotificationListenerService` push-alert bridge.
 *
 * ## Match rules (tightened after v1.6.0 review, body-scoped in v1.6.3)
 *
 * All alert lookups use [TRUST_WINDOW_MS] (10 min) — a longer window on
 * direct-number matches was found to be too permissive. Each alert in
 * the window gets two chances to allow:
 *
 *   1. **Anchored digit match against the body**: the last 7 digits of
 *      the caller appear in the alert *body* AS A WHOLE DIGIT RUN. The
 *      anchor prevents a 7-digit order ID / tracking number / PIN
 *      inside a longer digit sequence from accidentally unblocking a
 *      completely unrelated caller.
 *
 *      v1.6.3: restricted to the body only. Previously the match ran
 *      against `title + "\n" + body`, and since `\n` counts as a
 *      non-digit boundary, a standalone 7-digit run in a notification
 *      title (order ID "Order #5551234", delivery PIN, tracking
 *      number) could allow an unrelated caller whose last-7 happened
 *      to match. Trust phrases are still evaluated against the full
 *      searchText — a phrase like "Your driver" in the title is a
 *      legitimate signal.
 *
 *   2. **Package-gated phrase match**: the alert title or body contains
 *      a trust phrase (`your driver`, `out for delivery`, …) AND the
 *      phrase is allowed to fire for the alert's package. Verification
 *      /MFA phrases only match for messaging apps that actually send
 *      SMS codes — an Outlook MFA push no longer unblocks random callers.
 */
internal class PushAlertChecker : IChecker {
    override val priority = CheckerPriority.PUSH_ALERT_BRIDGE
    override val name = "push_alert"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_PUSH_ALERT] ?: true

    companion object {
        /** Shared TTL for both match paths — 10 minutes. */
        private const val TRUST_WINDOW_MS = 10L * 60L * 1000L

        /**
         * Packages that legitimately send SMS verification / OTP codes
         * that sometimes get followed by a human callback. Verification
         * phrases only fire for these.
         */
        private val VERIFICATION_SENDERS = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.microsoft.android.smsorganizer",
        )

        /**
         * Packages with a meaningful "appointment" / meeting-reminder
         * surface. Everything else (including Outlook mail notifications
         * that happen to contain the word "calendar") is a non-signal.
         */
        private val CALENDAR_APPS = setOf(
            "com.google.android.calendar",
        )

        /**
         * Gated trust phrase. When [allowedFromPackages] is `null` the
         * phrase applies regardless of sender; otherwise only alerts
         * from one of the listed packages can fire it.
         */
        internal data class TrustPhrase(
            val regex: Regex,
            val allowedFromPackages: Set<String>? = null,
        ) {
            fun appliesTo(pkg: String): Boolean =
                allowedFromPackages == null || pkg in allowedFromPackages
        }

        /**
         * Phrases that imply "this app is about to cause an unknown call
         * to ring". Loosened forms that matched too widely in v1.6.0 —
         * bare "outside", bare "calendar" — have been dropped.
         */
        val TRUST_PHRASES: List<TrustPhrase> = listOf(
            // Rideshare / delivery — broad, any sender
            TrustPhrase(Regex("(?i)your driver")),
            TrustPhrase(Regex("(?i)your (ride|trip) is (arriving|nearby|close)")),
            TrustPhrase(Regex("(?i)arriving (in |now|soon)")),
            TrustPhrase(Regex("(?i)(is |has |arriving |i'?m |we'?re )outside")),
            TrustPhrase(Regex("(?i)your (order|package|delivery|shipment)")),
            TrustPhrase(Regex("(?i)delivery (driver|partner|courier)")),
            TrustPhrase(Regex("(?i)out for delivery")),
            // Verification — only from messaging apps that actually send SMS codes
            TrustPhrase(
                Regex("(?i)(verify|verification) (code|pin)"),
                allowedFromPackages = VERIFICATION_SENDERS,
            ),
            TrustPhrase(
                Regex("(?i)(one-?time|otp) (passcode|password|pin|code)"),
                allowedFromPackages = VERIFICATION_SENDERS,
            ),
            // Appointment reminders — only from calendar apps
            TrustPhrase(
                Regex("(?i)appointment reminder"),
                allowedFromPackages = CALENDAR_APPS,
            ),
        )

        /**
         * Anchor a digit suffix so it only matches a standalone 7-digit
         * run. Lookbehind / lookahead reject adjacent digits — prevents
         * "5551234" from matching inside "15551234567".
         */
        internal fun anchoredDigitRegex(last7: String): Regex =
            Regex("(?<!\\d)${Regex.escape(last7)}(?!\\d)")
    }

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val alerts = PushAlertRegistry.snapshot(ttlMs = TRUST_WINDOW_MS)
        if (alerts.isEmpty()) return null

        val last7 = ctx.number.filter { it.isDigit() }.takeLast(7)
        val digitMatcher = if (last7.length == 7) anchoredDigitRegex(last7) else null

        for (alert in alerts) {
            // Anchored direct-number match — body only (see class docs).
            // Titles often carry short standalone digit runs (order IDs,
            // tracking numbers, delivery PINs) that would otherwise allow
            // an unrelated caller whose last-7 happened to match.
            if (digitMatcher != null && digitMatcher.containsMatchIn(alert.body)) {
                return BlockResult.allow("push_alert")
            }
            // Package-gated phrase match — full searchText (title+body).
            // Phrases like "Your driver" legitimately appear in titles.
            val matched = TRUST_PHRASES.any { phrase ->
                phrase.appliesTo(alert.packageName) && phrase.regex.containsMatchIn(alert.searchText)
            }
            if (matched) {
                return BlockResult.allow("push_alert")
            }
        }
        return null
    }
}
