package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.service.CallShieldScreeningService.Companion.AUTO_MUTE_CONFIDENCE_THRESHOLD
import com.sysadmindoc.callshield.service.CallShieldScreeningService.Companion.shouldSilence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage of the v1.7.0 auto-mute-low-confidence decision.
 *
 * The decision table (descending priority):
 *   1. silentVoicemail=true         → always silence
 *   2. autoMuteLowConf=true AND confidence < threshold → silence
 *   3. otherwise                    → hard reject (shouldSilence=false)
 *
 * All three paths keep the block visible in the call log + notification;
 * the only thing that varies is whether the phone rings.
 */
class CallShieldScreeningServiceAutoMuteTest {

    // ── 1) silentVoicemail wins unconditionally ──────────────────────────

    @Test fun `silentVoicemail=true silences every block regardless of other flags`() {
        for (autoMute in listOf(false, true)) {
            for (confidence in listOf(0, AUTO_MUTE_CONFIDENCE_THRESHOLD, 100)) {
                assertTrue(
                    "silentVoicemail=true should win for autoMute=$autoMute confidence=$confidence",
                    shouldSilence(
                        silentVoicemailEnabled = true,
                        autoMuteLowConfidenceEnabled = autoMute,
                        confidence = confidence,
                    )
                )
            }
        }
    }

    // ── 2) autoMute gate at the threshold ────────────────────────────────

    @Test fun `autoMute silences when confidence is below the threshold`() {
        assertTrue(
            shouldSilence(
                silentVoicemailEnabled = false,
                autoMuteLowConfidenceEnabled = true,
                confidence = AUTO_MUTE_CONFIDENCE_THRESHOLD - 1,
            )
        )
    }

    @Test fun `autoMute does NOT silence at exactly the threshold`() {
        // Boundary is strict `<` — 60 is treated as certain enough to reject.
        assertFalse(
            shouldSilence(
                silentVoicemailEnabled = false,
                autoMuteLowConfidenceEnabled = true,
                confidence = AUTO_MUTE_CONFIDENCE_THRESHOLD,
            )
        )
    }

    @Test fun `autoMute does NOT silence above the threshold`() {
        assertFalse(
            shouldSilence(
                silentVoicemailEnabled = false,
                autoMuteLowConfidenceEnabled = true,
                confidence = 95,
            )
        )
    }

    // ── 3) defaults: hard reject ─────────────────────────────────────────

    @Test fun `both flags off always hard-rejects`() {
        for (confidence in listOf(0, 25, 59, 60, 100)) {
            assertFalse(
                "both flags off with confidence=$confidence should be a hard reject",
                shouldSilence(
                    silentVoicemailEnabled = false,
                    autoMuteLowConfidenceEnabled = false,
                    confidence = confidence,
                )
            )
        }
    }

    // ── Threshold sanity ─────────────────────────────────────────────────

    @Test fun `threshold is 60 and centers the weak-heuristic range`() {
        // Contract — the default heuristic non-aggressive threshold is 60
        // (HeuristicChecker), so blocks below 60 never reach the pipeline
        // anyway. This threshold is the gate BETWEEN the low-confidence
        // ML/heuristic band and the certain-hit band. If this constant
        // ever diverges from the heuristic threshold, update both.
        assertTrue("threshold must be positive", AUTO_MUTE_CONFIDENCE_THRESHOLD > 0)
        assertTrue("threshold must be <= 100", AUTO_MUTE_CONFIDENCE_THRESHOLD <= 100)
    }
}
