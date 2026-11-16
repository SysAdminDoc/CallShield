package com.sysadmindoc.callshield.service

import androidx.datastore.preferences.core.preferencesOf
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.MeetingModeChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldScreeningServiceAnswerHangUpTest {
    @Test
    fun `answer and hang up decision has full truth table`() {
        // Every combination of the five inputs, one bit each. Answering while
        // roaming can be charged, so roaming falls back to reject.
        for (bits in 0 until 32) {
            val enabled = bits and 1 != 0
            val silenceWins = bits and 2 != 0
            val permissionsGranted = bits and 4 != 0
            val busy = bits and 8 != 0
            val roaming = bits and 16 != 0
            assertEquals(
                "combination $bits",
                enabled && !silenceWins && permissionsGranted && !busy && !roaming,
                CallShieldScreeningService.shouldAnswerAndHangUp(
                    enabled = enabled,
                    silenceWins = silenceWins,
                    permissionsGranted = permissionsGranted,
                    busy = busy,
                    roaming = roaming,
                ),
            )
        }
    }

    @Test
    fun `only the spam call just handed off is ignored by the busy check`() {
        for (bits in 0 until 16) {
            val controllerBusy = bits and 1 != 0
            val handingOff = bits and 2 != 0
            val telephonyOffhook = bits and 4 != 0
            val audioInCall = bits and 8 != 0
            // A call the controller is still answering always counts. Otherwise the phone
            // reading as on a call counts, unless that call is the one just ended for this one.
            val expected = controllerBusy || (!handingOff && (telephonyOffhook || audioInCall))
            assertEquals(
                "combination $bits",
                expected,
                CallShieldScreeningService.answerHangUpBusy(
                    controllerBusy = controllerBusy,
                    handingOff = handingOff,
                    telephonyOffhook = telephonyOffhook,
                    audioInCall = audioInCall,
                ),
            )
        }
        // The case this exists for: offhook and in-call audio from the call being ended.
        assertFalse(CallShieldScreeningService.answerHangUpBusy(false, true, true, true))
        assertTrue(CallShieldScreeningService.answerHangUpBusy(false, false, true, false))
    }

    @Test
    fun `a meeting-mode silence is never answered and hung up`() {
        // Meeting mode says nothing against the caller, so with every delivery
        // setting off its verdict still has to read as a silence here, or an
        // unknown caller during a meeting gets answered and dropped.
        val prefs = preferencesOf(SpamRepository.KEY_ANSWER_HANG_UP to true)

        assertTrue(
            CallShieldScreeningService.blockSilenceWins(
                prefs,
                confidence = 100,
                categoryAction = CategoryCallAction.INHERIT,
                reason = MeetingModeChecker.MATCH_SOURCE,
            ),
        )
        assertFalse(
            CallShieldScreeningService.blockSilenceWins(
                prefs,
                confidence = 100,
                categoryAction = CategoryCallAction.INHERIT,
                reason = "database",
            ),
        )
    }

    @Test
    fun `delivery settings that silence a block keep it out of answer and hang up`() {
        val silentVoicemail = preferencesOf(SpamRepository.KEY_SILENT_VOICEMAIL to true)
        val autoMute = preferencesOf(SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE to true)

        assertTrue(CallShieldScreeningService.blockSilenceWins(silentVoicemail, 100, CategoryCallAction.INHERIT, "database"))
        assertTrue(CallShieldScreeningService.blockSilenceWins(autoMute, 10, CategoryCallAction.INHERIT, "heuristic"))
        assertTrue(CallShieldScreeningService.blockSilenceWins(preferencesOf(), 100, CategoryCallAction.SILENCE, "database"))
        assertFalse(CallShieldScreeningService.blockSilenceWins(preferencesOf(), 100, CategoryCallAction.BLOCK, "database"))
    }
}
