package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingModeCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoom = "us.zoom.videomeetings"
    private val teams = "com.microsoft.teams"

    private val zoomPicked =
        preferencesOf(
            SpamRepository.KEY_MEETING_MODE to true,
            SpamRepository.KEY_MEETING_MODE_APPS to setOf(zoom),
        )

    private fun checker(
        inMeeting: Set<String>,
        contacts: Set<String> = emptySet(),
    ) = MeetingModeChecker(
        appContext = context,
        spamHeuristics = SpamHeuristics(),
        activeMeetingApps = { inMeeting },
        isContact = { _, number -> number in contacts },
    )

    private fun ctx(
        prefs: Preferences,
        number: String = "+12125550123",
        realtimeCall: Boolean = true,
        smsBody: String? = null,
    ) = CheckContext(appContext = context, number = number, realtimeCall = realtimeCall, prefs = prefs, smsBody = smsBody)

    @Test
    fun `silences an unknown caller while a picked app is in a meeting`() =
        runBlocking {
            val result = checker(inMeeting = setOf(zoom)).check(ctx(zoomPicked))

            assertEquals(true, result?.shouldBlock)
            assertEquals(MeetingModeChecker.MATCH_SOURCE, result?.matchSource)
            assertEquals(BlockReasonCode.MEETING_MODE, result?.reasonCode)
            assertEquals("Zoom", result?.description)
        }

    @Test
    fun `a meeting in an app the user didn't pick changes nothing`() =
        runBlocking {
            assertNull(checker(inMeeting = setOf(teams)).check(ctx(zoomPicked)))
        }

    @Test
    fun `no meeting in progress lets the call ring`() =
        runBlocking {
            assertNull(checker(inMeeting = emptySet()).check(ctx(zoomPicked)))
        }

    @Test
    fun `contacts always ring during a meeting`() =
        runBlocking {
            val contact = "+12125550123"
            assertNull(checker(inMeeting = setOf(zoom), contacts = setOf(contact)).check(ctx(zoomPicked, number = contact)))
        }

    @Test
    fun `only live calls with the setting on and an app picked are checked`() =
        runBlocking {
            val checker = checker(inMeeting = setOf(zoom))
            assertTrue(checker.isEnabled(ctx(zoomPicked)))
            assertFalse(checker.isEnabled(ctx(zoomPicked, realtimeCall = false)))
            assertFalse(checker.isEnabled(ctx(zoomPicked, smsBody = "hello")))
            assertFalse(
                checker.isEnabled(
                    ctx(
                        preferencesOf(
                            SpamRepository.KEY_MEETING_MODE to false,
                            SpamRepository.KEY_MEETING_MODE_APPS to setOf(zoom),
                        ),
                    ),
                ),
            )
            assertFalse(checker.isEnabled(ctx(preferencesOf(SpamRepository.KEY_MEETING_MODE to true))))
        }

    @Test
    fun `an allow above it wins in the pipeline`() =
        runBlocking {
            val allow =
                object : IChecker {
                    override val priority = CheckerPriority.PUSH_ALERT_BRIDGE
                    override val name = "push_alert"

                    override suspend fun check(ctx: CheckContext) = BlockResult.allow("push_alert")
                }
            val chain = listOf(allow, checker(inMeeting = setOf(zoom))).sortedByDescending { it.priority }

            val result = CheckerPipeline.run(chain, ctx(zoomPicked))

            assertEquals(false, result?.shouldBlock)
            assertEquals("push_alert", result?.matchSource)
        }

    @Test
    fun `meeting mode is the last checker in the production call chain`() {
        IsolatedRepositoryFixture(context).use { fixture ->
            val entries = runBlocking { fixture.repository.traceRules("+12125550123") }.entries

            assertEquals(MeetingModeChecker.MATCH_SOURCE, entries.last().checkerName)
            assertTrue(entries.dropLast(1).all { it.priority > CheckerPriority.MEETING_MODE })
        }
    }
}
