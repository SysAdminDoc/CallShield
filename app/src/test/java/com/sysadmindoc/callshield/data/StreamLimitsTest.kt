package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamLimitsTest {
    @Test
    fun `reads content within the byte cap`() {
        val text = "hello world"
        val result = ByteArrayInputStream(text.toByteArray()).readTextBounded(maxBytes = 1024)
        assertEquals(text, result)
    }

    @Test
    fun `returns null when input exceeds the byte cap`() {
        val big = "x".repeat(2048)
        val result = ByteArrayInputStream(big.toByteArray()).readTextBounded(maxBytes = 1024)
        assertNull(result)
    }

    @Test
    fun `returns content exactly at the cap`() {
        val exact = "y".repeat(1024)
        val result = ByteArrayInputStream(exact.toByteArray()).readTextBounded(maxBytes = 1024)
        assertEquals(exact, result)
    }

    @Test
    fun `bounded byte reader preserves binary content`() {
        val bytes = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())

        val result = ByteArrayInputStream(bytes).readBytesBounded(maxBytes = bytes.size.toLong())

        assertTrue(bytes.contentEquals(requireNotNull(result)))
    }

    @Test
    fun `closes the stream on success and on rejection`() {
        var closedOk = false
        val ok =
            object : ByteArrayInputStream("small".toByteArray()) {
                override fun close() {
                    closedOk = true
                    super.close()
                }
            }
        ok.readTextBounded(maxBytes = 64)
        assertTrue(closedOk)

        var closedBig = false
        val big =
            object : ByteArrayInputStream("z".repeat(200).toByteArray()) {
                override fun close() {
                    closedBig = true
                    super.close()
                }
            }
        big.readTextBounded(maxBytes = 64)
        assertTrue(closedBig)
    }
}
