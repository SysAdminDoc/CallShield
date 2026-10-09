package com.sysadmindoc.callshield.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class RelativeTimeTest {
    private val now = 1_760_000_000_000L

    @Test
    fun `under a minute, or ahead of now, is left to the screen's own just now`() {
        assertNull(relativeTimeSpan(now - TimeUnit.SECONDS.toMillis(59), now))
        assertNull(relativeTimeSpan(now + TimeUnit.MINUTES.toMillis(5), now))
    }

    @Test
    fun `minutes and hours are spelled out`() {
        assertEquals("1 minute ago", relativeTimeSpan(now - TimeUnit.MINUTES.toMillis(1), now))
        assertEquals("5 minutes ago", relativeTimeSpan(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("3 hours ago", relativeTimeSpan(now - TimeUnit.HOURS.toMillis(3), now))
    }

    @Test
    fun `the short form is shorter and still says how long`() {
        val short = relativeTimeSpan(now - TimeUnit.MINUTES.toMillis(5), now, abbreviate = true)!!
        assertTrue(short, short.startsWith("5 min") && short.length < "5 minutes ago".length)
    }
}
