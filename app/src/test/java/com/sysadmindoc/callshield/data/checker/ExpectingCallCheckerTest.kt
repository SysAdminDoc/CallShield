package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.ExpectingCall
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
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Expecting a call" lets unknown callers ring for an hour, three hours or
 * until midnight. The user's own rules and a failed caller ID check still
 * block, and the window ends by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpectingCallCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/New_York")
    private val start = ZonedDateTime.of(2026, 9, 26, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 60L * 60L * 1_000L

    private fun prefs(until: Long): Preferences =
        preferencesOf(
            SpamRepository.KEY_EXPECTING_CALL_UNTIL to until,
            SpamRepository.KEY_CONTACTS_ONLY to true,
        )

    private fun call(
        prefs: Preferences,
        verificationStatus: Int? = null,
        smsBody: String? = null,
    ) = CheckContext(
        appContext = context,
        number = "+15551230000",
        smsBody = smsBody,
        realtimeCall = true,
        prefs = prefs,
        verificationStatus = verificationStatus,
    )

    @Test
    fun `each length ends when it says`() {
        assertEquals(start + hour, ExpectingCall.endsAt(ExpectingCall.Length.ONE_HOUR, start, zone))
        assertEquals(start + 3 * hour, ExpectingCall.endsAt(ExpectingCall.Length.THREE_HOURS, start, zone))
        // 22:30 local: midnight is 90 minutes away, on the next day.
        assertEquals(
            ZonedDateTime.of(2026, 9, 27, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ExpectingCall.endsAt(ExpectingCall.Length.UNTIL_MIDNIGHT, start, zone),
        )
    }

    @Test
    fun `an unknown caller rings during the window and not after it`() =
        runBlocking {
            val until = ExpectingCall.endsAt(ExpectingCall.Length.ONE_HOUR, start, zone)
            var now = start
            val checker = ExpectingCallChecker { now }

            assertTrue(checker.isEnabled(call(prefs(until))))
            val verdict = checker.check(call(prefs(until)))
            assertFalse(verdict.shouldBlock)
            assertEquals(BlockReasonCode.EXPECTING_CALL, verdict.reasonCode)

            now = until
            assertFalse(checker.isEnabled(call(prefs(until))))
            assertFalse(checker.isEnabled(call(emptyPreferences())))
        }

    @Test
    fun `a hidden caller rings during the window under Contacts only and meeting mode`() {
        val contactsOnly =
            preferencesOf(
                SpamRepository.KEY_EXPECTING_CALL_UNTIL to start + hour,
                SpamRepository.KEY_CONTACTS_ONLY to true,
                SpamRepository.KEY_BLOCK_UNKNOWN to true,
            )
        val zoom = "us.zoom.videomeetings"
        assertNull(ExpectingCall.withheldCallBlockSource(contactsOnly, start, meetingApp = null))
        assertNull(ExpectingCall.withheldCallBlockSource(contactsOnly, start, meetingApp = zoom))
        // Once it ends, the settings decide again.
        assertEquals("hidden_number", ExpectingCall.withheldCallBlockSource(contactsOnly, start + hour, meetingApp = zoom))
        val meetingOnly = preferencesOf(SpamRepository.KEY_EXPECTING_CALL_UNTIL to start + hour)
        assertEquals(MeetingModeChecker.MATCH_SOURCE, ExpectingCall.withheldCallBlockSource(meetingOnly, start + hour, meetingApp = zoom))
        assertNull(ExpectingCall.withheldCallBlockSource(emptyPreferences(), start, meetingApp = null))
    }

    @Test
    fun `an ended window is cleared once seen, so a clock stepped back finds it closed`() =
        runBlocking {
            val fixture = IsolatedRepositoryFixture(context)
            try {
                val repo = fixture.repository
                repo.setExpectingCallUntil(start + hour)
                // Still running: nothing is cleared.
                repo.clearEndedExpectingCall(start + hour - 1)
                assertTrue(ExpectingCall.isActive(repo.readPrefsSnapshot(), start))

                assertTrue(ExpectingCall.hasEnded(repo.readPrefsSnapshot(), start + hour))
                repo.clearEndedExpectingCall(start + hour)
                val prefs = repo.readPrefsSnapshot()
                assertFalse(ExpectingCall.hasEnded(prefs, start + hour))
                // The clock steps back half an hour after the end.
                assertFalse(ExpectingCall.isActive(prefs, start + hour / 2))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `texts are not affected`() =
        runBlocking {
            val checker = ExpectingCallChecker { start }

            assertFalse(checker.isEnabled(call(prefs(start + hour), smsBody = "Your parcel is on its way")))
        }

    @Test
    fun `contacts-only mode stands aside while the window runs`() =
        runBlocking {
            var now = start
            val contactsOnly = ContactsOnlyChecker(context, SpamHeuristics(), contactsReadable = { true }, now = { now })

            assertFalse(contactsOnly.isEnabled(call(prefs(start + hour))))
            now = start + hour
            assertTrue(contactsOnly.isEnabled(call(prefs(start + hour))))
        }

    @Test
    fun `a failed caller ID check and the user's own rules still win`() =
        runBlocking {
            listOf(
                CheckerPriority.STIR_SHAKEN,
                CheckerPriority.USER_BLOCKLIST,
                CheckerPriority.SYSTEM_BLOCK_LIST,
                CheckerPriority.WILDCARD_RULE,
                CheckerPriority.HASH_WILDCARD_RULE,
            ).forEach { assertTrue(it > CheckerPriority.EXPECTING_CALL) }
            listOf(
                CheckerPriority.TEMPORARY_ALLOW,
                CheckerPriority.PREFIX_MATCH,
                CheckerPriority.GITHUB_DATABASE,
                CheckerPriority.HEURISTIC,
                CheckerPriority.ML_SCORER,
                CheckerPriority.TIME_BLOCK,
            ).forEach { assertTrue(it < CheckerPriority.EXPECTING_CALL) }

            val chain = listOf(StirShakenChecker(), ExpectingCallChecker { start }).sortedByDescending { it.priority }

            @Suppress("DEPRECATION")
            val spoofed = call(prefs(start + hour), verificationStatus = android.telecom.Connection.VERIFICATION_STATUS_FAILED)

            assertTrue(CheckerPipeline.run(chain, spoofed)!!.shouldBlock)
            assertFalse(CheckerPipeline.run(chain, call(prefs(start + hour)))!!.shouldBlock)
        }
}
