package com.sysadmindoc.callshield.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTest {

    @Test
    fun `race returns first decisive result without waiting for slow losers`() = runBlocking {
        val startedAt = System.currentTimeMillis()

        val result = race(
            competitors = listOf("slow", "winner"),
            timeoutMillis = 1_000L,
            decisive = { it == "winner" },
            onTimeout = "timeout",
        ) { competitor ->
            if (competitor == "winner") {
                delay(40L)
                "winner"
            } else {
                delay(700L)
                "slow"
            }
        }

        val elapsed = System.currentTimeMillis() - startedAt
        assertEquals("winner", result)
        assertTrue("race waited for loser for ${elapsed}ms", elapsed < 300L)
    }

    @Test
    fun `race timeout returns without waiting for unfinished competitors`() = runBlocking {
        val startedAt = System.currentTimeMillis()

        val result = race(
            competitors = listOf("slow"),
            timeoutMillis = 50L,
            decisive = { it == "winner" },
            onTimeout = "timeout",
        ) {
            delay(700L)
            "slow"
        }

        val elapsed = System.currentTimeMillis() - startedAt
        assertEquals("timeout", result)
        assertTrue("race waited past timeout for ${elapsed}ms", elapsed < 300L)
    }

    @Test
    fun `race returns timeout value when all competitors are non decisive`() = runBlocking {
        val result = race(
            competitors = listOf(1, 2),
            timeoutMillis = 1_000L,
            decisive = { it > 10 },
            onTimeout = -1,
        ) { competitor ->
            delay(20L)
            competitor
        }

        assertEquals(-1, result)
    }
}
