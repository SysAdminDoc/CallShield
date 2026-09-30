package com.sysadmindoc.callshield.data

import android.telephony.TelephonyManager.CALL_STATE_IDLE
import android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
import android.telephony.TelephonyManager.CALL_STATE_RINGING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownCallWindowTest {
    private var now = 1_000_000L
    private val window = UnknownCallWindow { now }
    private val caller = "+12025550143"

    @Test
    fun `a code during an unknown call gets one warning`() {
        window.callStarted(caller)
        now += 3 * MINUTE

        assertEquals(caller, window.openCaller())
        assertTrue(window.claimWarning(caller))
        assertNull("one warning per call", window.openCaller())
        assertFalse("the second reader of the same text", window.claimWarning(caller))
    }

    @Test
    fun `the window stays open for ten minutes after the call ends`() {
        val token = window.callStarted(caller)
        now += 20 * MINUTE
        window.callEnded(token)

        now += UnknownCallWindow.AFTER_CALL_MS
        assertEquals(caller, window.openCaller())
        now += 1
        assertNull(window.openCaller())
        assertFalse(window.claimWarning(caller))
    }

    @Test
    fun `no call means no warning, and a call whose end is never seen runs out`() {
        assertNull(window.openCaller())

        window.callStarted(caller)
        now += UnknownCallWindow.MAX_CALL_MS + 1
        assertNull(window.openCaller())
    }

    @Test
    fun `a new call gets its own warning and the old call's end doesn't close it`() {
        val first = window.callStarted(caller)
        assertTrue(window.claimWarning(caller))

        window.callStarted(OTHER_CALLER)
        window.callEnded(first)
        now += UnknownCallWindow.AFTER_CALL_MS + 1

        assertEquals(OTHER_CALLER, window.openCaller())
        assertFalse("a warning names the call it's for", window.claimWarning(caller))
    }

    @Test
    fun `the watcher closes the call when the phone goes idle`() =
        runBlocking {
            val states = ArrayDeque(listOf(CALL_STATE_IDLE, CALL_STATE_RINGING, CALL_STATE_OFFHOOK, CALL_STATE_OFFHOOK, CALL_STATE_OFFHOOK))
            val token = window.callStarted(caller)

            window.watchUntilEnded(token, callState = { states.removeFirstOrNull() ?: CALL_STATE_IDLE }, sleep = { now += it })

            // One poll before it rang, three while it was up: it ended 4 seconds in.
            now += UnknownCallWindow.AFTER_CALL_MS
            assertEquals(caller, window.openCaller())
            now += 1
            assertNull(window.openCaller())
        }

    @Test
    fun `without the phone state the call ends when it rang`() =
        runBlocking {
            val token = window.callStarted(caller)

            window.watchUntilEnded(token, callState = { null }, sleep = { error("never waits") })

            now += UnknownCallWindow.AFTER_CALL_MS + 1
            assertNull(window.openCaller())
        }

    @Test
    fun `a call that never shows up ends after the ring wait`() =
        runBlocking {
            val token = window.callStarted(caller)

            window.watchUntilEnded(token, callState = { CALL_STATE_IDLE }, sleep = { now += it })

            // It ended when the ring wait ran out, so ten minutes after that is still open.
            now += UnknownCallWindow.AFTER_CALL_MS
            assertEquals(caller, window.openCaller())
            now += 1
            assertNull(window.openCaller())
        }

    @Test
    fun `callers the user trusts never open the window`() {
        listOf("manual_whitelist", "emergency_contact", "contact_whitelist", "emergency_floor", "emergency_callback").forEach {
            assertFalse(it, UnknownCallWindow.countsAsUnknown(it))
        }
        listOf("", "expecting_call", "temporary_allow", "caller_name_trust").forEach {
            assertTrue(it, UnknownCallWindow.countsAsUnknown(it))
        }
    }

    @Test
    fun `codes are told apart from other texts with numbers`() {
        listOf(
            "G-482913 is your Google verification code.",
            "Your Chase code is 48291300. Don't share it.",
            "Your PIN is 4821",
            "Tu código de acceso es 5521",
            "Use 553901 to sign in. Code expires in 10 minutes.",
        ).forEach { assertTrue(it, UnknownCallWindow.looksLikeOneTimeCode(it)) }

        listOf(
            "See you at 1234 Main St at 7",
            "Your order 58213 has shipped",
            "Use promo code FALL for 20% off",
            "",
            "Your code is 482913. " + "x".repeat(400),
        ).forEach { assertFalse(it, UnknownCallWindow.looksLikeOneTimeCode(it)) }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val OTHER_CALLER = "+13125550199"
    }
}
