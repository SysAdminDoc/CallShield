package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class FeedMirrorTest {
    @After
    fun clear() = FeedMirror.resetForTest()

    @Test
    fun `a usable base gets a trailing slash so repository paths append cleanly`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master/",
            FeedMirror.normalize(" https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master "),
        )
        assertEquals("https://mirror.example.test/callshield/", FeedMirror.normalize("https://mirror.example.test/callshield/#top"))
    }

    @Test
    fun `anything that isn't a plain https base is refused`() {
        assertNull(FeedMirror.normalize("http://mirror.example.test/"))
        assertNull(FeedMirror.normalize("https://user:secret@mirror.example.test/"))
        assertNull(FeedMirror.normalize("https://mirror.example.test/?token=1"))
        assertNull(FeedMirror.normalize("file:///sdcard/feeds/"))
        assertNull(FeedMirror.normalize(""))
    }

    @Test
    fun `urls are the base plus the repository path, and nothing without a mirror`() {
        assertNull(FeedMirror.urlFor("data/hot_numbers.json"))

        FeedMirror.set("https://mirror.example.test/callshield")

        assertEquals("https://mirror.example.test/callshield/data/hot_numbers.json", FeedMirror.urlFor("data/hot_numbers.json"))
    }

    @Test
    fun `a stored value that stopped being usable clears the mirror`() {
        FeedMirror.set("https://mirror.example.test/")
        FeedMirror.set("http://mirror.example.test/")

        assertNull(FeedMirror.baseUrl)
    }

    @Test
    fun `a fetch that starts before the stored setting arrives waits for it`() =
        runBlocking {
            FeedMirror.startLoading()
            val seen =
                async(Dispatchers.Default) {
                    FeedMirror.awaitLoaded(timeoutMs = 10_000)
                    FeedMirror.baseUrl
                }
            delay(100)
            assertFalse("returned before the setting arrived", seen.isCompleted)

            FeedMirror.set("https://mirror.example.test/")

            assertEquals("https://mirror.example.test/", withTimeout(5_000) { seen.await() })
        }

    @Test
    fun `nothing waits when no setting is being read`() =
        runBlocking {
            val waited = measureTimeMillis { FeedMirror.awaitLoaded(timeoutMs = 10_000) }

            assertTrue("waited $waited ms", waited < 1_000)
        }

    @Test
    fun `a setting that never arrives stops holding fetches after the wait`() =
        runBlocking {
            FeedMirror.startLoading()

            val waited = measureTimeMillis { FeedMirror.awaitLoaded(timeoutMs = 200) }

            assertTrue("waited $waited ms", waited in 200..5_000)
        }

    @Test
    fun `a failed read stops holding fetches at once`() =
        runBlocking {
            FeedMirror.startLoading()
            FeedMirror.loadFailed()

            val waited = measureTimeMillis { FeedMirror.awaitLoaded(timeoutMs = 10_000) }

            assertTrue("waited $waited ms", waited < 1_000)
        }
}
