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
 * ## How matches work
 *
 * For each alert in the TTL window:
 *   1. If the alert body contains the last 7 digits of the incoming
 *      number (formatted or raw), it's a direct match — allow.
 *   2. Otherwise check against [TRUST_PHRASES] — coarse English heuristics
 *      like "your driver", "verification code", "arriving", "your order".
 *      If the phrase matches AND the alert is within 10 minutes, allow
 *      with lower confidence.
 *
 * Rule-2 is coarser but catches the case where the delivery driver's
 * number isn't in the notification (common for pre-arrival pings).
 */
internal class PushAlertChecker : IChecker {
    override val priority = CheckerPriority.PUSH_ALERT_BRIDGE
    override val name = "push_alert"

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.prefs[SpamRepository.KEY_PUSH_ALERT] ?: true

    companion object {
        /** Phrases that imply "this app is about to cause an unknown call to ring". */
        val TRUST_PHRASES = listOf(
            Regex("(?i)your driver"),
            Regex("(?i)your (ride|trip) is (arriving|nearby|close)"),
            Regex("(?i)arriving (in |now|soon)"),
            Regex("(?i)(is |has )?outside"),
            Regex("(?i)your (order|package|delivery|shipment)"),
            Regex("(?i)delivery (driver|partner|courier)"),
            Regex("(?i)out for delivery"),
            Regex("(?i)(verify|verification) (code|pin)"),
            Regex("(?i)(one-?time|otp) (passcode|password|pin|code)"),
            Regex("(?i)appointment reminder"),
            Regex("(?i)calendar"),
        )

        private const val PHRASE_MATCH_TTL_MS = 10L * 60L * 1000L   // 10 minutes
    }

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val alerts = PushAlertRegistry.snapshot()
        if (alerts.isEmpty()) return null

        val last7 = ctx.number.filter { it.isDigit() }.takeLast(7)
        val now = System.currentTimeMillis()

        for (alert in alerts) {
            // Direct number match — strongest signal.
            if (last7.length == 7 && alert.searchText.contains(last7)) {
                return BlockResult.allow("push_alert")
            }
            // Coarser phrase match — only within a tighter TTL.
            if (now - alert.timestamp <= PHRASE_MATCH_TTL_MS) {
                if (TRUST_PHRASES.any { it.containsMatchIn(alert.searchText) }) {
                    return BlockResult.allow("push_alert")
                }
            }
        }
        return null
    }
}
