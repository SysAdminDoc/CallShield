package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
