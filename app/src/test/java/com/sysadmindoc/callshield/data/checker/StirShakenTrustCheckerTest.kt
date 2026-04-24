package com.sysadmindoc.callshield.data.checker

import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.VERIFICATION_STATUS_PASSED
import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.decidePure
import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.isEnabledPure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage of the v1.7.0 STIR/SHAKEN trust-allow path.
 *
 * The checker short-circuits the weaker downstream blockers when the
 * carrier has signed PASSED for the calling number. This file locks the
 * behaviour contract in place so the hot-path decision table can't drift.
 *
 * android.telecom.Connection constant values (AOSP):
 *   VERIFICATION_STATUS_NOT_VERIFIED = 0
 *   VERIFICATION_STATUS_PASSED       = 1
 *   VERIFICATION_STATUS_FAILED       = 2
 */
class StirShakenTrustCheckerTest {

    private val notVerified = 0
    private val failed = 2

    // ── isEnabledPure ────────────────────────────────────────────────────

    @Test fun `enabled when setting on and verificationStatus present`() {
        assertTrue(isEnabledPure(settingEnabled = true, verificationStatus = VERIFICATION_STATUS_PASSED))
        assertTrue(isEnabledPure(settingEnabled = true, verificationStatus = failed))
        assertTrue(isEnabledPure(settingEnabled = true, verificationStatus = notVerified))
    }

    @Test fun `disabled when setting off regardless of verificationStatus`() {
        assertFalse(isEnabledPure(settingEnabled = false, verificationStatus = VERIFICATION_STATUS_PASSED))
        assertFalse(isEnabledPure(settingEnabled = false, verificationStatus = null))
    }

    @Test fun `disabled when verificationStatus is null`() {
        // Pre-Android 11 or SMS pipeline — nothing to trust.
        assertFalse(isEnabledPure(settingEnabled = true, verificationStatus = null))
    }

    // ── decidePure ───────────────────────────────────────────────────────

    @Test fun `PASSED produces an allow with matchSource stir_shaken_trusted`() {
        val result = decidePure(VERIFICATION_STATUS_PASSED)
        assertNotNull(result)
        assertFalse("PASSED must produce an allow, not a block", result!!.shouldBlock)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test fun `FAILED does not produce an allow here`() {
        // The FAILED case belongs to StirShakenChecker (block side). This
        // trust checker must return null so the pipeline falls through
        // to the block checker.
        assertNull(decidePure(failed))
    }

    @Test fun `NOT_VERIFIED returns null`() {
        // Carrier has no opinion; the pipeline should continue past us
        // and let the downstream layers decide.
        assertNull(decidePure(notVerified))
    }

    @Test fun `null verificationStatus returns null`() {
        // Defensive — even though isEnabled() would have rejected this
        // upstream, decide() must still degrade safely.
        assertNull(decidePure(null))
    }

    // ── Priority ordering sanity ─────────────────────────────────────────

    @Test fun `trust priority yields to every explicit user rule`() {
        // The carrier-signed PASS is a trust signal, not an override —
        // the user's explicit manual rules (whitelist, blocklist,
        // wildcard, prefix) are all authoritative against it. This test
        // is the regression guard for a v1.7.0 audit finding where the
        // initial priority was mis-placed above USER_BLOCKLIST and would
        // have let a carrier-verified spammer ring through a user-added
        // blocklist entry.
        assertTrue(
            "STIR trust must not override MANUAL_WHITELIST",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.MANUAL_WHITELIST,
        )
        assertTrue(
            "STIR trust must not override CONTACT_WHITELIST",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.CONTACT_WHITELIST,
        )
        assertTrue(
            "STIR trust must not override a user blocklist entry",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.USER_BLOCKLIST,
        )
        assertTrue(
            "STIR trust must not override a system block list entry",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.SYSTEM_BLOCK_LIST,
        )
        assertTrue(
            "STIR trust must not override a wildcard block rule",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.WILDCARD_RULE,
        )
        assertTrue(
            "STIR trust must not override a hash-wildcard block rule",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.HASH_WILDCARD_RULE,
        )
        assertTrue(
            "STIR trust must not override a prefix block rule",
            CheckerPriority.STIR_SHAKEN_TRUSTED < CheckerPriority.PREFIX_MATCH,
        )
    }

    @Test fun `trust priority beats weaker statistical layers`() {
        // Whole point of the allow: carrier-signed PASS short-circuits
        // heuristic / ML / campaign-burst / frequency-escalation blocks
        // that would otherwise fire on a legitimate caller. If this
        // ordering ever inverts, the feature has no effect.
        assertTrue(CheckerPriority.STIR_SHAKEN_TRUSTED > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.STIR_SHAKEN_TRUSTED > CheckerPriority.FREQUENCY_ESCALATION)
        assertTrue(CheckerPriority.STIR_SHAKEN_TRUSTED > CheckerPriority.HEURISTIC)
        assertTrue(CheckerPriority.STIR_SHAKEN_TRUSTED > CheckerPriority.CAMPAIGN_BURST)
        assertTrue(CheckerPriority.STIR_SHAKEN_TRUSTED > CheckerPriority.ML_SCORER)
    }
}
