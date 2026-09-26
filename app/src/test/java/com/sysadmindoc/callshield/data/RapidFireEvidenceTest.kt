package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rapid-fire counts a caller's recent blocks as evidence that it's a robocaller.
 * Rows that say nothing about the caller, because the block came from the
 * user's own schedule or mode, or the call was never blocked at all, mustn't
 * count toward it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RapidFireEvidenceTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun rows(
        number: String,
        matchReason: String,
        wasBlocked: Boolean = true,
    ) = (1..3).map { minutes ->
        BlockedCall(
            number = number,
            timestamp = now - minutes * 60_000L,
            matchReason = matchReason,
            wasBlocked = wasBlocked,
            logKey = "$number-$matchReason-$minutes",
        )
    }

    private suspend fun rapidFire(number: String): Boolean {
        val recent = fixture.dao.getRecentBlockedNumbers(now - 60 * 60_000L).map { it.number to it.timestamp }
        return SpamHeuristics.isRapidFire(recent, number)
    }

    @Test
    fun `quiet hours, contacts only and non-blocked rows are no evidence`() =
        runBlocking {
            val quietHours = "+12125550141"
            val contactsOnly = "+12125550142"
            val diagnostic = "+12125550143"
            val exemption = "+12125550144"
            fixture.dao.insertBlockedCalls(
                rows(quietHours, "time_block") +
                    rows(contactsOnly, "contacts_only") +
                    rows(diagnostic, "pipeline_diagnostic", wasBlocked = false) +
                    rows(exemption, "emergency_floor", wasBlocked = false),
            )

            assertEquals(emptyList<String>(), fixture.dao.getRecentBlockedNumbers(now - 60 * 60_000L).map { it.matchReason })
            for (number in listOf(quietHours, contactsOnly, diagnostic, exemption)) {
                assertFalse(number, rapidFire(number))
            }
        }

    @Test
    fun `three spam blocks still make a caller rapid-fire`() =
        runBlocking {
            val spammer = "+12125550145"
            fixture.dao.insertBlockedCalls(rows(spammer, "heuristic"))

            assertTrue(rapidFire(spammer))
        }
}
