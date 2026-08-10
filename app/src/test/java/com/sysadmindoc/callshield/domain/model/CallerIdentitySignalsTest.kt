package com.sysadmindoc.callshield.domain.model

import com.sysadmindoc.callshield.data.checker.CheckerPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdentitySignalsTest {
    @Test
    fun `A lowers risk only with Android carrier PASS and C raises it`() {
        val a = CallerIdentity(verificationStatus = 1, passport = passport("A"))
        val b = CallerIdentity(verificationStatus = 1, passport = passport("B"))
        val c = CallerIdentity(verificationStatus = 1, passport = passport("C"))
        val missing = CallerIdentity(verificationStatus = 1, passport = passport(null))
        val unverifiedA = CallerIdentity(verificationStatus = 0, passport = passport("A"))

        assertTrue(CallerIdentitySignals.assess(a).probabilityAdjustment < 0.0)
        assertTrue(CallerIdentitySignals.assess(b).probabilityAdjustment < 0.0)
        assertTrue(CallerIdentitySignals.assess(c).probabilityAdjustment > 0.0)
        assertEquals(0.0, CallerIdentitySignals.assess(missing).probabilityAdjustment, 0.0)
        assertEquals(0.0, CallerIdentitySignals.assess(unverifiedA).probabilityAdjustment, 0.0)
        assertTrue(CallerIdentitySignals.adjustProbability(0.80, a) < 0.80)
        assertTrue(CallerIdentitySignals.adjustProbability(0.80, c) > 0.80)
    }

    @Test
    fun `DNO and line type evidence is bounded additive risk`() {
        val dno = CallerIdentity(dnoStatus = DnoStatus.LISTED)
        val unassigned = CallerIdentity(dnoStatus = DnoStatus.UNASSIGNED)
        val lines = CallerIdentity(lineType = LineType.VOIP)
        val prepaid = CallerIdentity(lineType = LineType.PREPAID)

        assertTrue(CallerIdentitySignals.assess(dno).probabilityAdjustment > 0.0)
        assertTrue(CallerIdentitySignals.assess(unassigned).probabilityAdjustment > 0.0)
        assertTrue(CallerIdentitySignals.assess(lines).probabilityAdjustment > 0.0)
        assertTrue(CallerIdentitySignals.assess(prepaid).probabilityAdjustment > 0.0)
        assertTrue(CallerIdentitySignals.adjustProbability(0.90, dno) <= 1.0)
        assertTrue(CallerIdentitySignals.adjustProbability(0.01, CallerIdentity(passport = passport("A"))) >= 0.0)
    }

    @Test
    fun `metadata descriptions stay neutral and campaign remains ahead of ML`() {
        val identity =
            CallerIdentity(
                verificationStatus = 1,
                passport = passport("A").copy(richCallData = RichCallData(name = "Bank")),
                dnoStatus = DnoStatus.LISTED,
                lineType = LineType.VOIP,
            )
        val description = CallerIdentitySignals.describe(identity).lowercase()

        assertTrue(description.contains("identity evidence"))
        assertTrue(description.contains("not a spam verdict"))
        assertFalse(description.contains("safe"))
        assertFalse(description.contains("trusted"))
        assertTrue(CheckerPriority.CAMPAIGN_BURST > CheckerPriority.ML_SCORER)
        assertTrue(CheckerPriority.EMERGENCY_FLOOR > CheckerPriority.ML_SCORER)
    }

    private fun passport(attestation: String?): ParsedPassport =
        ParsedPassport(
            typ = "passport",
            algorithm = "ES256",
            certificateUrl = "https://example.com/cert",
            issuedAtEpochSeconds = 1_700_000_000L,
            originTelephoneNumber = "+12125550100",
            destinationTelephoneNumbers = listOf("+12125550101"),
            destinationUris = emptyList(),
            attestation = attestation,
        )
}
