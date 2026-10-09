package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CallerCountryTest {
    @Test
    fun `a call from another country names it`() {
        assertEquals("GB", CallerCountry.abroad("+442079460018", "US"))
        assertEquals("US", CallerCountry.abroad("+12125550123", "GB"))
        assertEquals("FR", CallerCountry.abroad("+33123456789", "au"))
    }

    @Test
    fun `a call from home isn't labeled`() {
        assertNull(CallerCountry.abroad("+12125550123", "US"))
        assertNull(CallerCountry.abroad("+442079460018", "GB"))
        // Jersey shares +44 with the UK.
        assertNull(CallerCountry.abroad("+447797123456", "JE"))
    }

    @Test
    fun `North America is told apart by area code`() {
        assertEquals("one-ring scams come from here", "JM", CallerCountry.abroad("+18765550123", "US"))
        assertEquals("DO", CallerCountry.abroad("+18095550123", "US"))
        assertEquals("CA", CallerCountry.abroad("+14165550123", "US"))
        assertEquals("US", CallerCountry.abroad("+12125550123", "CA"))
        assertNull(CallerCountry.abroad("+18765550123", "JM"))
    }

    @Test
    fun `a toll-free or unlisted North American code isn't guessed`() {
        assertNull("a Canadian bank's toll-free line", CallerCountry.abroad("+18005550123", "CA"))
        assertNull(CallerCountry.abroad("+18885550123", "JM"))
        assertNull("600 is Canada's, but the table doesn't say", CallerCountry.abroad("+16005550123", "CA"))
        assertNull(CallerCountry.abroad("+18005550123", "US"))
    }

    @Test
    fun `the US and its territories are one home`() {
        assertNull(CallerCountry.abroad("+17875550123", "US"))
        assertNull(CallerCountry.abroad("+12125550123", "PR"))
    }

    @Test
    fun `nothing is guessed without a full international number or a home region`() {
        assertNull(CallerCountry.abroad("2079460018", "US"))
        assertNull(CallerCountry.abroad("+442079460018", null))
        assertNull(CallerCountry.abroad("+4420", "US"))
        assertNull(CallerCountry.abroad("+1876555012", "US"))
        assertNull(CallerCountry.abroad("+442079460018", "ZZ"))
    }

    @Test
    fun `a shared calling code is named for its main country`() {
        assertEquals("RU", CallerCountry.abroad("+74951234567", "US"))
        assertEquals("GB", CallerCountry.abroad("+447797123456", "US"))
    }

    @Test
    fun `Russia and Kazakhstan are told apart`() {
        assertEquals("KZ", CallerCountry.abroad("+77012345678", "US"))
        assertEquals("RU", CallerCountry.abroad("+79161234567", "KZ"))
        assertEquals("KZ", CallerCountry.abroad("+77172123456", "RU"))
        assertNull(CallerCountry.abroad("+77012345678", "KZ"))
        assertNull(CallerCountry.abroad("+74951234567", "RU"))
    }

    @Test
    fun `the country name follows the language`() {
        assertEquals("Jamaica", CallerCountry.displayName("JM", Locale.US))
        assertEquals("Royaume-Uni", CallerCountry.displayName("GB", Locale.FRANCE))
    }
}
