package com.sysadmindoc.callshield.data

import android.telephony.TelephonyManager
import kotlinx.coroutines.delay

/**
 * The last call CallShield let ring from someone the user doesn't know, so a
 * one-time code that arrives during it, or up to [AFTER_CALL_MS] after it,
 * can carry a warning. Imposters "from the bank" ask for the code the bank
 * just sent. Kept in memory only: it matters for minutes, and nothing about
 * the call is written down.
 */
class UnknownCallWindow(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Call(
        val id: Long,
        val caller: String,
        val startedAt: Long,
    ) {
        var endedAt: Long? = null
        var warned = false
    }

    private val lock = Any()
    private var call: Call? = null
    private var nextId = 0L

    /** A call from [caller] ("" when the number was hidden) was let through. Returns the token [callEnded] takes. */
    fun callStarted(caller: String): Long =
        synchronized(lock) {
            val id = ++nextId
            call = Call(id, caller, clock())
            id
        }

    fun callEnded(token: Long) {
        synchronized(lock) {
            val current = call?.takeIf { it.id == token && it.endedAt == null } ?: return
            current.endedAt = clock()
        }
    }

    /** The caller a code arriving now should be warned about, or null when there's none or the warning was given. */
    fun openCaller(): String? =
        synchronized(lock) {
            val current = call ?: return null
            current.caller.takeIf { !current.warned && isOpen(current) }
        }

    /** Takes the one warning [caller]'s call gets. False when it's gone, so two apps reading the same text warn once. */
    fun claimWarning(caller: String): Boolean =
        synchronized(lock) {
            val current = call ?: return false
            if (current.caller != caller || current.warned || !isOpen(current)) return false
            current.warned = true
            true
        }

    /**
     * Waits for the call behind [token] to end, then closes it. Screening runs
     * before the phone rings, so it first waits up to [RING_START_MS] for the
     * call to show up. A null [callState] means the phone state can't be read,
     * and the call counts as ended when it rang.
     */
    suspend fun watchUntilEnded(
        token: Long,
        callState: () -> Int?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ) {
        val start = clock()
        while (callState() == TelephonyManager.CALL_STATE_IDLE && clock() - start < RING_START_MS) sleep(POLL_MS)
        while (callState().let { it != null && it != TelephonyManager.CALL_STATE_IDLE } && clock() - start < MAX_CALL_MS) {
            sleep(POLL_MS)
        }
        callEnded(token)
    }

    internal fun clear() {
        synchronized(lock) { call = null }
    }

    private fun isOpen(call: Call): Boolean {
        val now = clock()
        val ended = call.endedAt
        return if (ended == null) now - call.startedAt <= MAX_CALL_MS else now - ended <= AFTER_CALL_MS
    }

    companion object {
        const val AFTER_CALL_MS = 10L * 60L * 1000L

        /** A call whose end is never seen stops counting after this long. */
        const val MAX_CALL_MS = 4L * 60L * 60L * 1000L
        const val RING_START_MS = 30_000L
        private const val POLL_MS = 1_000L
        private const val MAX_CODE_TEXT_LENGTH = 320

        val shared = UnknownCallWindow()

        /** Callers the user already trusts never open the window. */
        private val TRUSTED_SOURCES =
            setOf("manual_whitelist", "emergency_contact", "contact_whitelist", "emergency_floor", "emergency_callback")

        fun countsAsUnknown(matchSource: String): Boolean = matchSource !in TRUSTED_SOURCES

        private val codeWord = Regex("(?iu)(?<!\\p{L})(?:code|c[oó]digo|codice|pin|otp|passcode|clave|kod|kode)(?!\\p{L})")
        private val codeDigits = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

        /**
         * A short text carrying a code: the OTP floor's strict test, or a code
         * word next to four to eight digits. Looser than the floor because it
         * only ever adds a warning, never lets a text through.
         */
        fun looksLikeOneTimeCode(body: String): Boolean {
            if (body.isBlank() || body.length > MAX_CODE_TEXT_LENGTH) return false
            if (SmsContentAnalyzer.isVerificationMessage(body)) return true
            return codeWord.containsMatchIn(body) && codeDigits.containsMatchIn(body)
        }
    }
}
