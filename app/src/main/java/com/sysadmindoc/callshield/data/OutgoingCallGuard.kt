package com.sysadmindoc.callshield.data

import androidx.annotation.StringRes
import com.sysadmindoc.callshield.R
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether an outgoing call is held until the user confirms it.
 *
 * Callback scams (wangiri, premium-rate lines) only pay when the victim dials
 * out, so a number CallShield already flags gets one warning before it rings.
 * Everything else goes through untouched, and so does a flagged number the
 * user has chosen to call anyway.
 */
internal object OutgoingCallGuard {
    /**
     * Telecom cancels the call when the redirection app hasn't answered within
     * five seconds, so the lookup gets a small share of that and the call goes
     * through untouched when it runs over.
     */
    const val DECISION_BUDGET_MS = 1_500L

    /** How long a "Call anyway" tap lets the same number through. */
    const val BYPASS_WINDOW_MS = 120_000L

    enum class Reason(
        @param:StringRes val textRes: Int,
    ) {
        USER_BLOCKLIST(R.string.outgoing_hold_reason_blocklist),
        DATABASE(R.string.outgoing_hold_reason_database),
        PREMIUM_RATE(R.string.outgoing_hold_reason_premium),
        WANGIRI(R.string.outgoing_hold_reason_wangiri),
    }

    sealed interface Decision {
        data object Proceed : Decision

        data class Hold(
            val reason: Reason,
        ) : Decision
    }

    private val bypassUntil = ConcurrentHashMap<String, Long>()

    /** Premium-rate and callback-scam country codes, which need no lookup. */
    fun ruleReason(number: String): Reason? =
        when {
            SpamHeuristics.isInternationalPremium(number) -> Reason.PREMIUM_RATE
            SpamHeuristics.isWangiriCountryCode(number) -> Reason.WANGIRI
            else -> null
        }

    /**
     * [trusted] covers contacts and the user's allow list, which outrank every
     * flag the way they do for incoming calls. [listed] reports a blocklist or
     * database row. Both run inside [budgetMs]; running over places the call.
     */
    suspend fun decide(
        number: String,
        nowElapsed: Long,
        trusted: suspend (String) -> Boolean,
        listed: suspend (String) -> Reason?,
        budgetMs: Long = DECISION_BUDGET_MS,
    ): Decision {
        if (number.isBlank() || EmergencyNumberFloor.isProtected(number) || isBypassed(number, nowElapsed)) {
            return Decision.Proceed
        }
        return withTimeoutOrNull(budgetMs) {
            if (trusted(number)) {
                Decision.Proceed
            } else {
                (listed(number) ?: ruleReason(number))?.let(Decision::Hold) ?: Decision.Proceed
            }
        } ?: Decision.Proceed
    }

    /** The user chose "Call anyway": the next calls to [number] go straight through for a while. */
    fun allow(
        number: String,
        nowElapsed: Long,
    ) {
        bypassUntil[number] = nowElapsed + BYPASS_WINDOW_MS
    }

    fun isBypassed(
        number: String,
        nowElapsed: Long,
    ): Boolean {
        val until = bypassUntil[number] ?: return false
        if (until > nowElapsed) return true
        bypassUntil.remove(number, until)
        return false
    }

    internal fun resetForTests() {
        bypassUntil.clear()
    }
}
