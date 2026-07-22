package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneIdentityCanonicalizerTest {
    @Test
    fun `US region canonicalizes a national number to E164`() {
        val canonicalizer = canonicalizer("US", mapOf("2125551234" to "+12125551234"))

        assertEquals("+12125551234", canonicalizer.canonicalizePhone("(212) 555-1234"))
    }

    @Test
    fun `non NANP region never silently adds country code one`() {
        val canonicalizer = canonicalizer("GB", mapOf("02079460018" to "+442079460018"))

        assertEquals("+442079460018", canonicalizer.canonicalizePhone("020 7946 0018"))
    }

    @Test
    fun `missing region preserves national digits without assuming NANP`() {
        val canonicalizer = canonicalizer(null, emptyMap())

        assertEquals("2125551234", canonicalizer.canonicalizePhone("212-555-1234"))
    }

    @Test
    fun `existing E164 numbers and short codes bypass regional formatting`() {
        var formatterCalls = 0
        val canonicalizer =
            PhoneIdentityCanonicalizer("US") { _, _ ->
                formatterCalls++
                error("Formatter should not run")
            }

        assertEquals("+442079460018", canonicalizer.canonicalizePhone("+44 20 7946 0018"))
        assertEquals("911", canonicalizer.canonicalizePhone("911"))
        assertEquals(0, formatterCalls)
    }

    @Test
    fun `opaque sender IDs stay distinct from each other and phone identities`() {
        val canonicalizer = canonicalizer("US", emptyMap())

        assertEquals("BANK-ALERT", canonicalizer.canonicalizeIdentity("Bank-Alert"))
        assertEquals("PAYPAL", canonicalizer.canonicalizeIdentity("PayPal"))
        assertEquals("+12125551234", canonicalizer.canonicalizeIdentity("+1 212 555 1234"))
    }

    @Test
    fun `lettered sender IDs never canonicalize to blank`() {
        val canonicalizer = canonicalizer("US", emptyMap())

        // Non-Latin scripts keep a readable canonical form.
        assertEquals("СПАМ", canonicalizer.canonicalizeIdentity("Спам"))
        // Decorated senders that no readable form fits fall back to a stable
        // hashed token — blank would let the sender opt out of SMS screening.
        val decorated = canonicalizer.canonicalizeIdentity("«Bonus»")
        assertTrue(decorated.startsWith("OPAQUE:"))
        assertEquals(decorated, canonicalizer.canonicalizeIdentity("«Bonus»"))
        assertNotEquals(decorated, canonicalizer.canonicalizeIdentity("«Prize»"))
    }

    private fun canonicalizer(
        region: String?,
        formatted: Map<String, String>,
    ): PhoneIdentityCanonicalizer = PhoneIdentityCanonicalizer(region) { number, _ -> formatted[number] }
}
