package com.sysadmindoc.callshield.data

import android.app.Application
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.CallFrequencyEvidenceTest.CallRow
import com.sysadmindoc.callshield.data.CallFrequencyEvidenceTest.FakeCallLog
import com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
    private lateinit var callLog: FakeCallLog
    private val number = "+12125550123"
    private val now = System.currentTimeMillis()
    private val meetingTimes = (1..3).map { now - it * 60_000L }

    @Before
    fun setUp() {
        callLog = Robolectric.setupContentProvider(FakeCallLog::class.java, CallLog.Calls.CONTENT_URI.authority)
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
        runBlocking {
            fixture.dao.insertBlockedCalls(
                meetingTimes.mapIndexed { index, time ->
                    BlockedCall(number = number, timestamp = time, matchReason = "meeting_mode", logKey = "meeting-$index")
                } + BlockedCall(number = number, timestamp = now - 10 * 60_000L, matchReason = "heuristic", logKey = "heuristic"),
            )
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun frequencyVerdict(): PipelineTraceVerdict =
        fixture.repository
            .traceRules(number)
            .entries
            .single { it.checkerName == "frequency" }
            .verdict

    @Test
    fun `meeting silences are stored under their own reason code`() =
        runBlocking {
            val stored = fixture.dao.readBlockedCallsBatch(Long.MAX_VALUE, Long.MAX_VALUE, 10)

            assertEquals(3, stored.count { it.reasonCode == BlockReasonCode.MEETING_MODE })
        }

    @Test
    fun `meeting silences don't count toward the repeat-caller frequency`() =
        runBlocking {
            // Android logs a call the screener silenced as missed, with a reason from API 31.
            // 15 s off the meeting rows, past the 10 s match window, so the reason alone must do it.
            callLog.rows =
                meetingTimes.map {
                    CallRow(number, it + 15_000L, CallLog.Calls.MISSED_TYPE, CallLog.Calls.USER_MISSED_CALL_SCREENING_SERVICE_SILENCED)
                }
            assertEquals(PipelineTraceVerdict.PASS, frequencyVerdict())

            // The same three calls with nothing to say they were silenced do escalate,
            // so the provider is being read and the pass above means something.
            callLog.rows = listOf(3, 4, 5).map { CallRow(number, now - it * 3_600_000L, CallLog.Calls.MISSED_TYPE) }
            assertEquals(PipelineTraceVerdict.BLOCK, frequencyVerdict())
        }

    @Test
    @Config(sdk = [29])
    fun `before API 31 the matching meeting rows keep silences out of the frequency`() =
        runBlocking {
            // No missed-call reason exists yet; the local meeting rows are the only evidence.
            callLog.rows = meetingTimes.map { CallRow(number, it + 1_500L, CallLog.Calls.MISSED_TYPE) }

            assertEquals(PipelineTraceVerdict.PASS, frequencyVerdict())
        }

    @Test
    fun `meeting silences don't count as recent blocks for rapid-fire detection`() =
        runBlocking {
            assertEquals(listOf("heuristic"), fixture.dao.getRecentBlockedNumbers(now - 60 * 60_000L).map { it.matchReason })
        }
}
