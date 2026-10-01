package com.sysadmindoc.callshield.data

import android.telephony.TelephonyManager
import kotlinx.coroutines.delay

/**
 * The last call CallShield let ring from someone the user doesn't know, so a
 * one-time code that arrives during it, or up to [AFTER_CALL_MS] after it,
 * can carry a warning. Imposters "from the bank" ask for the code the bank
 * just sent. The text that carries the code can start a fresh process, so
 * the caller saves a [snapshot] and hands it to [restore] there.
 */
class UnknownCallWindow(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Call(
        val id: Long,
        val caller: String,
        val startedAt: Long,
        val restored: Boolean = false,
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

    /** The current call as [restore] takes it, or null when there's none. */
    fun snapshot(): Saved? = synchronized(lock) { call?.let { Saved(it.caller, it.startedAt, it.endedAt, it.warned) } }

    /**
     * Takes back the call an earlier process saved, unless this one knows a
     * call already. One saved without an end lost its watcher mid-call, so it
     * counts for [RESTORED_OPEN_MS] from its start instead of [MAX_CALL_MS].
     */
    fun restore(saved: Saved) {
        synchronized(lock) {
            if (call != null) return
            call =
                Call(++nextId, saved.caller, saved.startedAt, restored = true).apply {
                    endedAt = saved.endedAt
                    warned = saved.warned
                }
        }
    }

    /** Whether there's a call a code could still be warned about. */
    fun isOpen(): Boolean = synchronized(lock) { call?.let(::isOpen) == true }

    internal fun clear() {
        synchronized(lock) { call = null }
    }

    private fun isOpen(call: Call): Boolean {
        val now = clock()
        val ended = call.endedAt
        val unended = if (call.restored) RESTORED_OPEN_MS else MAX_CALL_MS
        return if (ended == null) now - call.startedAt <= unended else now - ended <= AFTER_CALL_MS
    }

    data class Saved(
        val caller: String,
        val startedAt: Long,
        val endedAt: Long?,
        val warned: Boolean,
    )

    companion object {
        const val AFTER_CALL_MS = 10L * 60L * 1000L

        /** A call whose end is never seen stops counting after this long. */
        const val MAX_CALL_MS = 4L * 60L * 60L * 1000L

        /** How long a restored call whose end nobody saw still counts, from its start. */
        const val RESTORED_OPEN_MS = 60L * 60L * 1000L
        const val RING_START_MS = 30_000L
        private const val POLL_MS = 1_000L
        private const val MAX_CODE_TEXT_LENGTH = 320

        val shared = UnknownCallWindow()

        /** Callers the user already trusts never open the window. */
        private val TRUSTED_SOURCES =
            setOf("manual_whitelist", "emergency_contact", "contact_whitelist", "emergency_floor", "emergency_callback")

        fun countsAsUnknown(matchSource: String): Boolean = matchSource !in TRUSTED_SOURCES

        private val codeWord = Regex("(?iu)(?<!\\p{L})(?:code|c[oó]digo|codice|pin|otp|passcode|clave|kod|kode|kodu|m?tan)(?!\\p{L})")

        // Chinese, Japanese and Korean write the word against its neighbours.
        private val cjkCodeWord = Regex("验证码|驗證碼|校验码|校驗碼|动态码|動態碼|认证码|認證碼|確認コード|認証コード|인증\\s?번호")

        // Codes are also sent split in two: 123-456, 123 456, 1234 5678.
        private val codeDigits = Regex("(?<!\\d)(?:\\d{4,8}|\\d{3}[ -]\\d{3}|\\d{4}[ -]\\d{4})(?!\\d)")

        /**
         * A short text carrying a code: the OTP floor's strict test, or a code
         * word next to four to eight digits. Looser than the floor because it
         * only ever adds a warning, never lets a text through.
         */
        fun looksLikeOneTimeCode(body: String): Boolean {
            if (body.isBlank() || body.length > MAX_CODE_TEXT_LENGTH) return false
            if (SmsContentAnalyzer.isVerificationMessage(body)) return true
            return (codeWord.containsMatchIn(body) || cjkCodeWord.containsMatchIn(body)) && codeDigits.containsMatchIn(body)
        }
    }
}
