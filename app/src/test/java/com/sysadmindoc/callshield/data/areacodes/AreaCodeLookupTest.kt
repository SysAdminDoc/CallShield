package com.sysadmindoc.callshield.data.areacodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AreaCodeLookupTest {
    @Test
    fun `a number outside +1 has no area code even with ten digits`() {
        // Singapore +65 9123 4567 read as 659 (Birmingham, AL) and Auckland
        // +64 9 as 649 (Turks and Caicos), and the caller-ID popup showed them.
        assertNotNull(AreaCodeLookup.lookup("+16595550123", "US"))
        assertNull(AreaCodeLookup.getAreaCode("+6591234567"))
        assertNull(AreaCodeLookup.lookup("+6591234567", "US"))
        assertNull(AreaCodeLookup.lookup("+6491234567", null))
        assertNull(AreaCodeLookup.getRegionCode("+6591234567"))
    }

    @Test
    fun `North American numbers keep their area code in every spelling`() {
        assertEquals("205", AreaCodeLookup.getAreaCode("+12055550123"))
        assertEquals("205", AreaCodeLookup.getAreaCode("+1 (205) 555-0123"))
        assertEquals("205", AreaCodeLookup.getAreaCode("+ 1 205 555 0123"))
        assertEquals("205", AreaCodeLookup.getAreaCode("12055550123"))
        assertEquals("205", AreaCodeLookup.getAreaCode("2055550123"))
        assertEquals("AL", AreaCodeLookup.getRegionCode("+12055550123"))
    }

    @Test
    fun `a number without a plus follows the phone's home region`() {
        // On a phone from China, 130 1234 5678 is a mobile number; it read as
        // Rockville, MD in Recent calls.
        assertNull(AreaCodeLookup.lookup("13012345678", "CN"))
        assertNull(AreaCodeLookup.getAreaCode("13012345678", "CN"))
        assertEquals("Rockville, MD", AreaCodeLookup.lookup("13012345678", "US"))
        // Canada and the Caribbean dial +1 too, and an unknown region keeps the old reading.
        assertEquals("301", AreaCodeLookup.getAreaCode("3015550123", "CA"))
        assertEquals("301", AreaCodeLookup.getAreaCode("3015550123", "JM"))
        assertEquals("301", AreaCodeLookup.getAreaCode("3015550123", null))
        // A + number never depends on the home region.
        assertEquals("Rockville, MD", AreaCodeLookup.lookup("+13015550123", "CN"))
    }
}
