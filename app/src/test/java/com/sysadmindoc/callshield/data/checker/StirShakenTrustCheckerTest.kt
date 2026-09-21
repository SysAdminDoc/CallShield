package com.sysadmindoc.callshield.data.checker

import com.sysadmindoc.callshield.data.SourceEvidenceCodec
import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.VERIFICATION_STATUS_PASSED
import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.decidePure
import com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker.Companion.isEnabledPure
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.model.SpamNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    // ── Database evidence ────────────────────────────────────────────────
    // A PASSED attestation proves line ownership, not that the call is
    // wanted, so it only overrides a database row whose evidence is stale.

    private val today = LocalDate.of(2026, 9, 21)

    private fun row(
        lastSeen: String,
        source: String = "github",
        evidence: List<SourceEvidenceJson> = emptyList(),
    ) = SpamNumber(
        number = "+12125550100",
        type = "robocall",
        lastSeen = lastSeen,
        source = source,
        evidenceJson = SourceEvidenceCodec.encode(evidence),
    )

    private fun evidence(
        sourceId: String,
        lastSeen: String,
        tier: String = "unverified",
    ) = SourceEvidenceJson(
        sourceId = sourceId,
        evidenceType = "user_report",
        license = "fixture",
        attribution = "fixture",
        lastSeen = lastSeen,
        confidenceTier = tier,
    )

    @Test fun `PASSED yields to a database row seen within the last year`() {
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("2026-03-01"), today))
        // Inclusive at exactly 365 days.
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("2025-09-21"), today))
    }

    @Test fun `PASSED still rings through a row whose newest evidence is older than a year`() {
        val result = decidePure(VERIFICATION_STATUS_PASSED, row("2025-09-20"), today)
        assertNotNull(result)
        assertFalse(result!!.shouldBlock)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test fun `a newer evidence record keeps an old row current`() {
        val recent = evidence("ftc_complaints", lastSeen = "2026-09-01")
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("2016-08-17", evidence = listOf(recent)), today))
    }

    @Test fun `community reports count by their date, whatever their tier says`() {
        // The tier comes from the row's total report count, legacy complaints
        // included, so one community report on a 2015 FCC row reads as
        // corroborated. It must not keep that row current forever.
        val oldCorroborated = evidence("community_reports", lastSeen = "2024-01-01", tier = "corroborated")
        val recentSingle = evidence("community_reports", lastSeen = "2026-06-01")
        assertNotNull(decidePure(VERIFICATION_STATUS_PASSED, row("2015-03-01", evidence = listOf(oldCorroborated)), today))
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("2015-03-01", evidence = listOf(recentSingle)), today))
    }

    @Test fun `a number trending right now keeps its stale database row current`() {
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("2016-01-01"), today, trending = true))
        assertNotNull(decidePure(VERIFICATION_STATUS_PASSED, row("2016-01-01"), today, trending = false))
    }

    @Test fun `a trending hot-list row counts as current without a date`() {
        assertNull(decidePure(VERIFICATION_STATUS_PASSED, row("", source = "hot_list"), today))
    }

    @Test fun `an undated or unreadable row is no evidence of recency`() {
        assertNotNull(decidePure(VERIFICATION_STATUS_PASSED, row(""), today))
        assertNotNull(decidePure(VERIFICATION_STATUS_PASSED, row("last tuesday"), today))
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
