package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SmsReceiver.reassembleBody] — the pure multipart-SMS
 * reassembly with a hard 16 KB cap. Covers the DoS surface where a hostile
 * delivery claims hundreds of parts.
 */
class SmsReceiverReassemblyTest {
    @Test
    fun `joins ordered parts`() {
        assertEquals(
            "hello world!",
            SmsReceiver.reassembleBody(listOf("hello ", "world", "!")),
        )
    }

    @Test
    fun `skips null parts`() {
        assertEquals("ab", SmsReceiver.reassembleBody(listOf("a", null, "b")))
    }

    @Test
    fun `empty list yields empty string`() {
        assertEquals("", SmsReceiver.reassembleBody(emptyList()))
    }

    @Test
    fun `caps total length at MAX_REASSEMBLED_BODY`() {
        val huge = List(500) { "x".repeat(1_000) } // 500 KB of parts
        val body = SmsReceiver.reassembleBody(huge)
        assertEquals(SmsReceiver.MAX_REASSEMBLED_BODY, body.length)
    }

    @Test
    fun `truncates the boundary-crossing part exactly at the cap`() {
        val prefix = "y".repeat(SmsReceiver.MAX_REASSEMBLED_BODY - 5)
        val body = SmsReceiver.reassembleBody(listOf(prefix, "zzzzzzzzzz"))
        assertEquals(SmsReceiver.MAX_REASSEMBLED_BODY, body.length)
        assertTrue(body.endsWith("zzzzz")) // only the first 5 z's fit
    }

    @Test
    fun `stops appending once the cap is reached`() {
        val atCap = "q".repeat(SmsReceiver.MAX_REASSEMBLED_BODY)
        val body = SmsReceiver.reassembleBody(listOf(atCap, "IGNORED"))
        assertEquals(SmsReceiver.MAX_REASSEMBLED_BODY, body.length)
        assertTrue(body.none { it == 'I' })
    }
}
