package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A call silenced because the user was in a meeting says nothing about the
 * caller, so it mustn't feed the repeat-caller and rapid-fire spam signals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MeetingModeEvidenceTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private val number = "+12125550123"
    private val since = 1_735_689_600_000L

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
        runBlocking {
            fixture.dao.insertBlockedCalls(
                (1..3).map { BlockedCall(number = number, timestamp = since + it, matchReason = "meeting_mode", logKey = "meeting-$it") } +
                    BlockedCall(number = number, timestamp = since + 10, matchReason = "heuristic", logKey = "heuristic"),
            )
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `meeting silences are stored under their own reason code`() =
        runBlocking {
            val stored = fixture.dao.readBlockedCallsBatch(Long.MAX_VALUE, Long.MAX_VALUE, 10)

            assertEquals(3, stored.count { it.reasonCode == BlockReasonCode.MEETING_MODE })
        }

    @Test
    fun `meeting silences don't count toward the repeat-caller frequency`() =
        runBlocking {
            val frequency =
                fixture.repository
                    .traceRules(number)
                    .entries
                    .single { it.checkerName == "frequency" }
            assertEquals(PipelineTraceVerdict.PASS, frequency.verdict)
        }

    @Test
    fun `meeting silences don't count as recent blocks for rapid-fire detection`() =
        runBlocking {
            assertEquals(listOf("heuristic"), fixture.dao.getRecentBlockedNumbers(since).map { it.matchReason })
        }
}
