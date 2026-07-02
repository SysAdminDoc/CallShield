package com.sysadmindoc.callshield.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StirShakenSemanticsTest {
    @Test
    fun `passport A wording authenticates metadata without caller approval language`() {
        val display = StirShakenSemantics.forPassportAttestation("A")

        assertTrue(display.headline.contains("attestation A"))
        assertTrue(display.description.contains("authorized to use this number"))
        assertTrue(display.description.contains("not a verdict"))
        assertNoApprovalLanguage(display)
    }

    @Test
    fun `passport B wording says number use was not fully confirmed`() {
        val display = StirShakenSemantics.forPassportAttestation("B")

        assertTrue(display.headline.contains("attestation B"))
        assertTrue(display.description.contains("did not fully attest"))
        assertTrue(display.description.contains("partial authentication"))
        assertNoApprovalLanguage(display)
    }

    @Test
    fun `passport C wording says only gateway was authenticated`() {
        val display = StirShakenSemantics.forPassportAttestation("C")

        assertTrue(display.headline.contains("attestation C"))
        assertTrue(display.description.contains("gateway"))
        assertTrue(display.description.contains("should not override local detection"))
        assertNoApprovalLanguage(display)
    }

    @Test
    fun `android passed wording notes full attestation details are unavailable`() {
        val display =
            StirShakenSemantics.forAndroidVerificationStatus(
                StirShakenSemantics.VERIFICATION_STATUS_PASSED,
            )

        assertTrue(display!!.headline.contains("authentication passed"))
        assertTrue(display.description.contains("not the full A/B/C PASSporT"))
        assertTrue(display.description.contains("not a verdict"))
        assertNoApprovalLanguage(display)
    }

    @Test
    fun `unknown android verification status has no display copy`() {
        val display = StirShakenSemantics.forAndroidVerificationStatus(-1)

        assertNull(display)
    }

    private fun assertNoApprovalLanguage(display: StirShakenDisplay) {
        val text = "${display.headline} ${display.description}".lowercase()
        assertFalse(text.contains("safe"))
        assertFalse(text.contains("trusted"))
        assertFalse(text.contains("green"))
    }
}
