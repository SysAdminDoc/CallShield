package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `a national NANP number gets a +1 lookup form only on a NANP home region`() {
        assertEquals("+16495550123", PhoneIdentityCanonicalizer.nanpE164Fallback("6495550123", "US"))
        assertEquals("+16495550123", PhoneIdentityCanonicalizer.nanpE164Fallback("16495550123", "ca"))
        assertEquals("+18765550123", PhoneIdentityCanonicalizer.nanpE164Fallback("8765550123", "JM"))

        // Already E.164, not NANP-shaped (area codes start 2-9), wrong length,
        // or a home region outside the NANP: nothing to add.
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("+16495550123", "US"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("0495550123", "US"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("10495550123", "US"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("5550123", "US"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("116495550123", "US"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("6495550123", "GB"))
        assertNull(PhoneIdentityCanonicalizer.nanpE164Fallback("6495550123", null))
    }

    @Test
    fun `equivalent forms list only the spellings a number can be stored under`() {
        // libphonenumber accepts 212 555 0101, so it's always stored as +1.
        val us = canonicalizer("US", mapOf("2125550101" to "+12125550101", "12125550101" to "+12125550101"))
        assertEquals(listOf("+12125550101"), us.equivalentForms("+12125550101"))

        // It rejects 649 555 0123, so every spelling can be on file.
        assertEquals(listOf("6495550123", "+16495550123", "16495550123"), us.equivalentForms("6495550123"))
        assertEquals(listOf("+16495550123", "6495550123", "16495550123"), us.equivalentForms("+16495550123"))
        assertEquals(listOf("16495550123", "+16495550123", "6495550123"), us.equivalentForms("16495550123"))

        // Outside the NANP, or for anything not NANP-shaped, there's just the one.
        assertEquals(listOf("6495550123"), canonicalizer("GB", emptyMap()).equivalentForms("6495550123"))
        assertEquals(listOf("+442079460018"), us.equivalentForms("+442079460018"))
        assertEquals(listOf("5550123"), us.equivalentForms("5550123"))
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
