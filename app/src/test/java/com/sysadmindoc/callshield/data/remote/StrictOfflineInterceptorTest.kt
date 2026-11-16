package com.sysadmindoc.callshield.data.remote

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strict offline mode means no request at all: the refusal comes from an
 * interceptor on the shared client, before a name is resolved or a socket is
 * opened. A loopback server counts every connection that reaches it, so
 * "no request" is checked at the socket and not by trusting an exception.
 */
class StrictOfflineInterceptorTest {
    private val connections = AtomicInteger()
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())

    init {
        Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        connections.incrementAndGet()
                        // Read the request head, then answer with an empty 200.
                        val input = socket.getInputStream()
                        val seen = StringBuilder()
                        while (!seen.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next < 0) break
                            seen.append(next.toChar())
                        }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    }
                } catch (_: IOException) {
                    // The test closed the server.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private val url = "http://127.0.0.1:${server.localPort}/check"

    /** A client built the way every caller builds one: from the shared client. */
    private val derived =
        HttpClient.shared
            .newBuilder()
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    @After
    fun tearDown() {
        server.close()
        StrictOffline.resetForTest()
    }

    private fun get(): String = derived.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    @Test
    fun `a client derived from the shared one is refused when offline mode is on, and nothing reaches the server`() {
        StrictOffline.set(true)

        val refused = assertThrows(StrictOfflineException::class.java) { get() }

        assertTrue(refused.isStrictOffline())
        assertEquals("no connection was opened", 0, connections.get())
    }

    @Test
    fun `the same client works when offline mode is off`() {
        StrictOffline.set(false)

        assertEquals("ok", get())
        assertEquals(1, connections.get())
    }

    @Test
    fun `turning the switch on and off takes effect on the next request`() {
        StrictOffline.set(false)
        assertEquals("ok", get())

        StrictOffline.set(true)
        assertThrows(StrictOfflineException::class.java) { get() }

        StrictOffline.set(false)
        assertEquals("ok", get())
        assertEquals("only the two requests made while it was off arrived", 2, connections.get())
    }

    @Test
    fun `an unknown state with the stored setting on is refused and the server is not reached`() {
        StrictOffline.forget()
        StrictOffline.installReader { true }

        assertThrows(StrictOfflineException::class.java) { get() }

        assertEquals(0, connections.get())
    }

    @Test
    fun `an unknown state with the stored setting off lets the request through`() {
        StrictOffline.forget()
        StrictOffline.installReader { false }

        assertEquals("ok", get())
    }

    @Test
    fun `an unknown state whose setting cannot be read counts as on`() {
        StrictOffline.forget()
        StrictOffline.installReader { throw IOException("settings file is unreadable") }

        assertThrows(StrictOfflineException::class.java) { get() }

        assertEquals(0, connections.get())
        assertTrue(StrictOffline.blocksRequests())
    }

    @Test
    fun `a state the collector has set wins over the reader`() {
        StrictOffline.installReader { true }
        StrictOffline.set(false)

        assertFalse(StrictOffline.blocksRequests())
        assertEquals("ok", get())
    }

    @Test
    fun `without a reader or the unit-test tripwire a request is refused`() {
        val previous = System.getProperty(HttpClient.UNIT_TEST_PROPERTY)
        try {
            System.clearProperty(HttpClient.UNIT_TEST_PROPERTY)
            StrictOffline.resetForTest()

            assertTrue(StrictOffline.blocksRequests())
        } finally {
            if (previous != null) System.setProperty(HttpClient.UNIT_TEST_PROPERTY, previous)
        }
    }

    @Test
    fun `the refusal is found through a wrapped cause and is a plain IOException for old callers`() {
        val wrapped = IOException("fetch failed", StrictOfflineException())

        assertTrue(wrapped.isStrictOffline())
        assertFalse(IOException("fetch failed", SocketTimeoutException("slow")).isStrictOffline())
    }

    @Test
    fun `a refused lookup reads as offline mode, not as unavailable`() {
        assertEquals(RemoteLookupStatus.OFFLINE_MODE, StrictOfflineException().toRemoteLookupStatus())
        assertEquals(RemoteLookupStatus.UNAVAILABLE, IOException("down").toRemoteLookupStatus())
        assertTrue(RemoteLookupStatus.OFFLINE_MODE.isFallback)
    }
}
