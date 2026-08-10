package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScreeningResponseGateTest {
    @Test
    fun `only the first response is delivered`() {
        val responses = mutableListOf<Int>()
        val gate = ScreeningResponseGate(responses::add)

        gate.respond(1)
        gate.respond(2)

        assertTrue(gate.hasResponded)
        assertEquals(1, responses.size)
        assertEquals(1, responses.single())
    }

    @Test
    fun `concurrent fallback paths still deliver exactly one response`() {
        val responses = mutableListOf<Int>()
        val gate = ScreeningResponseGate<Int> { response -> synchronized(responses) { responses += response } }
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(32)

        try {
            repeat(32) { response ->
                workers.execute {
                    ready.await()
                    gate.respond(response)
                    finished.countDown()
                }
            }
            ready.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            workers.shutdownNow()
        }

        assertTrue(gate.hasResponded)
        assertEquals(1, responses.size)
    }
}
