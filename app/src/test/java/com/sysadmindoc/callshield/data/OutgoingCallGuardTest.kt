package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.OutgoingCallGuard.Decision
import com.sysadmindoc.callshield.data.OutgoingCallGuard.Reason
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutgoingCallGuardTest {
    private val now = 1_000_000L
    private val lookups = mutableListOf<String>()

    private fun decide(
        number: String,
        trusted: Boolean = false,
        listed: Reason? = null,
        at: Long = now,
    ): Decision =
        runBlocking {
            OutgoingCallGuard.decide(
                number = number,
                nowElapsed = at,
                trusted = {
                    lookups += "trusted:$it"
                    trusted
                },
                listed = {
                    lookups += "listed:$it"
                    listed
                },
            )
        }

    @Before
    fun setUp() {
        OutgoingCallGuard.resetForTests()
    }

    @After
    fun tearDown() {
        OutgoingCallGuard.resetForTests()
    }

    @Test
    fun `an unflagged number is called straight through`() {
        assertEquals(Decision.Proceed, decide("+12125550101"))
    }

    @Test
    fun `blocklist and database numbers are held with their reason`() {
        assertEquals(Decision.Hold(Reason.USER_BLOCKLIST), decide("+12125550101", listed = Reason.USER_BLOCKLIST))
        assertEquals(Decision.Hold(Reason.DATABASE), decide("+12125550102", listed = Reason.DATABASE))
    }

    @Test
    fun `premium-rate and callback-scam codes are held without a database row`() {
        assertEquals(Decision.Hold(Reason.PREMIUM_RATE), decide("+19005550123"))
        assertEquals(Decision.Hold(Reason.WANGIRI), decide("+23276123456"))
        assertEquals(Decision.Hold(Reason.WANGIRI), decide("+18095550123"))
    }

    @Test
    fun `contacts and trusted numbers ring through even when flagged`() {
        assertEquals(Decision.Proceed, decide("+18095550123", trusted = true))
        assertEquals(Decision.Proceed, decide("+12125550101", trusted = true, listed = Reason.USER_BLOCKLIST))
    }

    @Test
    fun `emergency numbers are never held or looked up`() {
        assertEquals(Decision.Proceed, decide("911", listed = Reason.DATABASE))
        assertEquals(Decision.Proceed, decide("112", listed = Reason.DATABASE))
        assertTrue("an emergency call must not wait on a lookup", lookups.isEmpty())
    }

    @Test
    fun `call anyway lets the number through for its window and no longer`() {
        OutgoingCallGuard.allow("+19005550123", nowElapsed = now)

        assertEquals(Decision.Proceed, decide("+19005550123", at = now + 60_000L))
        assertTrue(OutgoingCallGuard.isBypassed("+19005550123", now + OutgoingCallGuard.BYPASS_WINDOW_MS - 1))
        assertFalse(OutgoingCallGuard.isBypassed("+19005550123", now + OutgoingCallGuard.BYPASS_WINDOW_MS))
        assertEquals(
            Decision.Hold(Reason.PREMIUM_RATE),
            decide("+19005550123", at = now + OutgoingCallGuard.BYPASS_WINDOW_MS + 1),
        )
        assertEquals("a bypass covers one number only", Decision.Hold(Reason.PREMIUM_RATE), decide("+19005550124"))
    }

    @Test
    fun `a lookup that runs over the budget places the call on time`() =
        runBlocking {
            val started = System.nanoTime()
            val decision =
                OutgoingCallGuard.decide(
                    number = "+19005550123",
                    nowElapsed = now,
                    trusted = { false },
                    listed = {
                        delay(10_000L)
                        Reason.DATABASE
                    },
                    budgetMs = 100L,
                )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(Decision.Proceed, decision)
            assertTrue("gave up after ${elapsedMs}ms", elapsedMs < 2_000L)
        }

    @Test
    fun `the lookup budget leaves room inside Telecom's five seconds`() {
        assertTrue(OutgoingCallGuard.DECISION_BUDGET_MS < 5_000L)
    }
}
