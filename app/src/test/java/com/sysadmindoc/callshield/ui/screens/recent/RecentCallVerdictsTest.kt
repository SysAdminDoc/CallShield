package com.sysadmindoc.callshield.ui.screens.recent

import android.app.Application
import android.content.Context
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Recent tab shows the verdict CallShield logged for each call, not the number's verdict today. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecentCallVerdictsTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A US SIM, so a call log row in national form reads as the same number as the logged block.
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")
        fixture = IsolatedRepositoryFixture(context)
        // The scorer flags 555 numbers on its own, which would make every number flagged now.
        runBlocking { fixture.repository.setMlScorer(false) }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a call CallShield blocked keeps the reason it logged, and an earlier call it let through isn't spam`() =
        runBlocking {
            val blockedAt = now - 60 * 60_000L
            fixture.dao.insertBlockedCalls(listOf(BlockedCall(number = CALLER, timestamp = blockedAt + 2_000L, matchReason = "heuristic", logKey = "block")))

            val calls =
                recentCallsWithVerdicts(
                    fixture.repository,
                    listOf(
                        CallLogEntry("2125550146", CallLog.Calls.BLOCKED_TYPE, blockedAt, 0),
                        CallLogEntry(CALLER, CallLog.Calls.INCOMING_TYPE, blockedAt - 60 * 60_000L, 30),
                    ),
                )

            assertEquals(listOf(true, false), calls.map { it.isSpam })
            assertEquals(listOf("heuristic", ""), calls.map { it.spamReason })
            assertEquals(listOf(null, null), calls.map { it.flaggedSinceReason })
        }

    @Test
    fun `a number blocked after its call says it was flagged later and stays out of Spam`() =
        runBlocking {
            val answered = CallLogEntry(CALLER, CallLog.Calls.INCOMING_TYPE, now - 30 * 60_000L, 45)
            fixture.repository.blockNumber(CALLER)
            val today = fixture.repository.isSpam(CALLER, realtimeCall = false)

            val call = recentCallsWithVerdicts(fixture.repository, listOf(answered)).single()

            assertEquals(false, call.isSpam)
            assertNotNull(today.matchSource.takeIf { today.isSpam })
            assertEquals(today.matchSource, call.flaggedSinceReason)
        }

    @Test
    fun `a meeting silence isn't a spam verdict`() =
        runBlocking {
            val silencedAt = now - 10 * 60_000L
            fixture.dao.insertBlockedCalls(listOf(BlockedCall(number = CALLER, timestamp = silencedAt, matchReason = "meeting_mode", logKey = "meeting")))

            val call = recentCallsWithVerdicts(fixture.repository, listOf(CallLogEntry(CALLER, CallLog.Calls.MISSED_TYPE, silencedAt, 0))).single()

            assertEquals(false, call.isSpam)
            assertEquals(null, call.flaggedSinceReason)
        }

    private companion object {
        const val CALLER = "+12125550146"
    }
}
