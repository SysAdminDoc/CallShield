package com.sysadmindoc.callshield.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedResponseBodyTest {
    @Test
    fun `reads body at byte limit`() {
        val body = "abcdef".toResponseBody("text/plain; charset=utf-8".toMediaType())

        val result = body.readUtf8Bounded(maxBytes = 6)

        assertEquals(BoundedResponseBody.Text("abcdef"), result)
        assertEquals(RemoteLookupStatus.FOUND, result.status())
    }

    @Test
    fun `rejects body with declared oversized length`() {
        val body = "abcdefg".toResponseBody("text/plain".toMediaType())

        val result = body.readUtf8Bounded(maxBytes = 6)

        assertTrue(result is BoundedResponseBody.Oversized)
        assertEquals(RemoteLookupStatus.BODY_TOO_LARGE, result.status())
    }

    @Test
    fun `returns empty status for empty bodies`() {
        val body = "".toResponseBody("text/plain".toMediaType())

        val result = body.readUtf8Bounded(maxBytes = 6)

        assertEquals(BoundedResponseBody.Empty, result)
        assertEquals(RemoteLookupStatus.EMPTY_BODY, result.status())
    }
}
