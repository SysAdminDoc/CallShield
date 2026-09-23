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
     * database row. Both run inside [budgetMs]. Running over places the call,
     * unless the number matches a premium-rate or callback-scam rule: those
     * need no lookup, so a slow contacts query can't wave one through, and
     * the user still has Call anyway. [bypassKey] is where a Call anyway pass
     * for this number would be stored (see [bypassKey]).
     */
    suspend fun decide(
        number: String,
        nowElapsed: Long,
        trusted: suspend (String) -> Boolean,
        listed: suspend (String) -> Reason?,
        budgetMs: Long = DECISION_BUDGET_MS,
        bypassKey: String = number,
    ): Decision {
        if (number.isBlank() || EmergencyNumberFloor.isProtected(number) || isBypassed(bypassKey, nowElapsed)) {
            return Decision.Proceed
        }
        val rule = ruleReason(number)
        return withTimeoutOrNull(budgetMs) {
            if (trusted(number)) {
                Decision.Proceed
            } else {
                (listed(number) ?: rule)?.let(Decision::Hold) ?: Decision.Proceed
            }
        } ?: rule?.let(Decision::Hold) ?: Decision.Proceed
    }

    /**
     * One key for every spelling of a number, so a Call anyway pass for
     * 649 555 0123 also covers +1 649 555 0123. The hold matches both
     * forms (see [PhoneIdentityCanonicalizer.nanpE164Fallback]), and a pass
     * that covered only one sent the redial into another hold.
     */
    fun bypassKey(
        canonical: String,
        homeRegionIso: String?,
    ): String = PhoneIdentityCanonicalizer.nanpE164Fallback(canonical, homeRegionIso) ?: canonical

    /** The user chose "Call anyway": the next calls under [key] go straight through for a while. */
    fun allow(
        key: String,
        nowElapsed: Long,
    ) {
        bypassUntil[key] = nowElapsed + BYPASS_WINDOW_MS
    }

    fun isBypassed(
        key: String,
        nowElapsed: Long,
    ): Boolean {
        val until = bypassUntil[key] ?: return false
        if (until > nowElapsed) return true
        bypassUntil.remove(key, until)
        return false
    }

    internal fun resetForTests() {
        bypassUntil.clear()
    }
}
