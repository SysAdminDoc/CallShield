package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rules > Blocked sorts newest first by when each number was blocked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlockTimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a block records its time, and changing its label keeps it`() {
        val before = System.currentTimeMillis()
        runBlocking { fixture.repository.blockNumber(NUMBER) }
        val blockedAt = row()?.blockedAt
        assertNotNull(blockedAt)
        assertTrue(blockedAt!! in before..System.currentTimeMillis())

        Thread.sleep(5)
        runBlocking { fixture.repository.blockNumber(NUMBER, description = "Fake warranty") }

        assertEquals("Fake warranty", row()?.description)
        assertEquals(blockedAt, row()?.blockedAt)
    }

    @Test
    fun `unblocking a database number clears its block time`() {
        runBlocking {
            fixture.dao.insertNumber(SpamNumber(number = NUMBER, type = "robocall", source = "github"))
            fixture.repository.blockNumber(NUMBER)
        }
        val blocked = row()!!
        assertNotNull(blocked.blockedAt)

        runBlocking { fixture.repository.unblockNumber(blocked) }

        assertEquals(false, row()?.isUserBlocked)
        assertNull(row()?.blockedAt)
    }

    @Test
    fun `the most-blocked counts cover blocked calls to the user's own blocks only`() {
        runBlocking {
            fixture.repository.blockNumber(NUMBER)
            fixture.dao.insertNumber(SpamNumber(number = OTHER, type = "robocall", source = "github"))
            fixture.dao.insertBlockedCall(BlockedCall(number = NUMBER, timestamp = 1L))
            fixture.dao.insertBlockedCall(BlockedCall(number = NUMBER, timestamp = 2L))
            fixture.dao.insertBlockedCall(BlockedCall(number = NUMBER, timestamp = 3L, wasBlocked = false))
            fixture.dao.insertBlockedCall(BlockedCall(number = OTHER, timestamp = 4L))
        }

        assertEquals(mapOf(NUMBER to 2), runBlocking { fixture.repository.observeUserBlockedHitCounts().first() })
    }

    private fun row(): SpamNumber? = runBlocking { fixture.dao.findByNumber(NUMBER) }

    private companion object {
        const val NUMBER = "+12125550101"
        const val OTHER = "+13105550102"
    }
}
