package com.sysadmindoc.callshield.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlaggedTextNumbersTest {
    private val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    private val repository = fixture.repository
    private val now = 1_800_000_000_000L
    private val sender = "+12025550143"
    private val callback = "+18003451234"
    private val text = "Your order is on hold. Call 1-800-345-1234 to cancel."

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a number in a flagged text is kept for 30 days`() =
        runBlocking {
            val seenAt = now - 29 * DAY
            repository.logFlaggedText(sender, text, "sms_content", 80, timestamp = seenAt)

            assertEquals(seenAt, repository.lastFlaggedTextSighting(callback, now))
            assertNull("30 days on it's forgotten", repository.lastFlaggedTextSighting(callback, seenAt + 30 * DAY))
            assertNull("the sender alone isn't a callback number", repository.lastFlaggedTextSighting("+13125550199", now))
        }

    @Test
    fun `a later flagged text moves the date forward`() =
        runBlocking {
            repository.logFlaggedText(sender, text, "sms_content", 80, timestamp = now - 20 * DAY)
            repository.logFlaggedText("+13125550199", "Final notice. Call 800.345.1234", "sms_content", 80, timestamp = now - DAY)

            assertEquals(now - DAY, repository.lastFlaggedTextSighting(callback, now))
        }

    @Test
    fun `a full text that repeats a cut-short notification still gives up its number`() =
        runBlocking {
            // The notification listener logs first, with the number cut off.
            repository.logFlaggedText(sender, "Your order is on hold. Call", "rcs_sms_content", 80, timestamp = now)
            repository.logFlaggedText(sender, text, "sms_content", 80, timestamp = now + 1_000L)

            assertEquals(now + 1_000L, repository.lastFlaggedTextSighting(callback, now + 2_000L))
        }

    private companion object {
        const val DAY = 86_400_000L
    }
}
