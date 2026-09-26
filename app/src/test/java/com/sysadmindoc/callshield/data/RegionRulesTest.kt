package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.checker.CallerNameBlockChecker
import com.sysadmindoc.callshield.data.checker.CallerNameTrustChecker
import com.sysadmindoc.callshield.data.checker.CheckerPriority
import com.sysadmindoc.callshield.data.checker.RegionBlockChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class RegionRulesTest {
    @Test
    fun `resolves US Canadian territory and toll free region codes`() {
        assertEquals("NY", RegionRules.regionCode("+12125550123"))
        assertEquals("ON", RegionRules.regionCode("+14165550123"))
        assertEquals("SK", RegionRules.regionCode("+13065550123"))
        assertEquals("VI", RegionRules.regionCode("+13405550123"))
        assertEquals("TF", RegionRules.regionCode("+18005550123"))
    }

    @Test
    fun `international and malformed numbers have no NANP region`() {
        assertNull(RegionRules.regionCode("+442071838750"))
        assertNull(RegionRules.regionCode("123"))
    }

    @Test
    fun `region parser normalizes and rejects unsupported codes`() {
        assertEquals(linkedSetOf("NY", "NJ", "ON", "TF"), RegionRules.parseRegionCodes("ny, NJ on; tf, ZZ"))
    }

    @Test
    fun `allowed region passes while other and international regions block`() {
        val allowed = setOf("NY", "NJ")

        assertFalse(RegionRules.isOutsideAllowedRegions("+12125550123", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+14155550123", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+442071838750", allowed))
    }

    @Test
    fun `new active area codes use the pinned NANP region`() {
        assertEquals("NY", RegionRules.regionCode("+14652340101"))
        assertEquals("AL", RegionRules.regionCode("+14832340101"))
        assertEquals("ON", RegionRules.regionCode("+19422340101"))
        assertEquals("Jacksonville, FL", AreaCodeLookup.lookup("+13245550123", null))
        assertEquals("Roanoke, VA", AreaCodeLookup.lookup("+18265550123", null))
        assertFalse(RegionRules.isOutsideAllowedRegions("+14652340101", setOf("NY")))
        assertNull(RegionBlockChecker.decidePure("+14652340101", setOf("NY")))
    }

    @Test
    fun `shared Canadian area codes allow each covered province or territory`() {
        assertEquals(setOf("NS", "PE"), AreaCodeLookup.getRegionCodes("+19022340101"))
        assertEquals(setOf("NT", "YT", "NU"), AreaCodeLookup.getRegionCodes("+18672340101"))
        assertFalse(RegionRules.isOutsideAllowedRegions("+19022340101", setOf("PE")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+18672340101", setOf("YT")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+19022340101", setOf("NY")))
    }

    @Test
    fun `an area code that may have entered service since the snapshot does not trigger a regional block`() {
        // 221 was unassigned in the pinned NANPA file, so a call from it may be a real new code.
        assertNull(AreaCodeLookup.lookup("+12215550123", null))
        assertTrue(AreaCodeLookup.mayBeNewAreaCode("+12215550123"))
        assertNull(RegionRules.regionCode("+12215550123"))
        val messages = mutableListOf<String>()
        val logger = Logger.getLogger(RegionRules::class.java.name)
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    messages.add(record.message)
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        try {
            assertFalse(RegionRules.isOutsideAllowedRegions("+12215550123", setOf("NY")))
            assertNull(RegionBlockChecker.decidePure("+12215550123", setOf("NY")))
            assertTrue(messages.any { it.contains("Unknown NANP area code 221") })
        } finally {
            logger.removeHandler(handler)
        }
        assertTrue(RegionRules.isOutsideAllowedRegions("+14155550123", setOf("NY")))
    }

    @Test
    fun `area codes that can't be assigned stay outside the allowed regions`() {
        // Reserved 999, N11 211, 555, non-geographic 521/600/700 and premium 900
        // have no region, and a caller showing one is usually spoofing it.
        listOf("+19995550123", "+12115550123", "+15555550123", "+15215550123", "+16005550123", "+17005550123", "+19005550123").forEach {
            assertFalse(it, AreaCodeLookup.mayBeNewAreaCode(it))
            assertTrue(it, RegionRules.isOutsideAllowedRegions(it, setOf("NY")))
        }
        // Allowing the area code by its +1 prefix still covers it.
        assertFalse(RegionRules.isOutsideAllowedRegions("+19995550123", setOf("NY", "+1999")))
    }

    @Test
    fun `empty region list fails open`() {
        assertFalse(RegionRules.isOutsideAllowedRegions("+442071838750", emptySet()))
    }

    @Test
    fun `presented name glob is case insensitive and whitespace normalized`() {
        val patterns = setOf("School District*", "ACME ?ANK")

        assertTrue(RegionRules.matchesPresentedName("  SCHOOL   DISTRICT 12 ", patterns))
        assertTrue(RegionRules.matchesPresentedName("acme bank", patterns))
        assertFalse(RegionRules.matchesPresentedName("Acme Credit Union", patterns))
    }

    @Test
    fun `exact caller name pattern does not act as substring`() {
        assertTrue(RegionRules.matchesPresentedName("City Hospital", setOf("CITY HOSPITAL")))
        assertFalse(RegionRules.matchesPresentedName("City Hospital Billing", setOf("CITY HOSPITAL")))
    }

    @Test
    fun `caller name checker returns allow only for a matching presented name`() {
        val match = CallerNameTrustChecker.decidePure("CITY SCHOOL", setOf("CITY *"))

        assertEquals(false, match?.shouldBlock)
        assertEquals("caller_name_trust", match?.matchSource)
        assertNull(CallerNameTrustChecker.decidePure(null, setOf("CITY *")))
    }

    @Test
    fun `caller name block checker rejects a matching presented name`() {
        val match = CallerNameBlockChecker.decidePure("MEDICARE BENEFITS CENTER", setOf("MEDICARE BENEFITS*"))

        assertEquals(true, match?.shouldBlock)
        assertEquals("caller_name", match?.matchSource)
        assertTrue(match?.description.orEmpty().contains("MEDICARE BENEFITS CENTER"))
        assertNull(CallerNameBlockChecker.decidePure("CITY SCHOOL", setOf("MEDICARE*")))
        assertNull(CallerNameBlockChecker.decidePure(null, setOf("MEDICARE*")))
    }

    @Test
    fun `region checker reports the out of region code`() {
        val result = RegionBlockChecker.decidePure("+14155550123", setOf("NY"))

        assertEquals(true, result?.shouldBlock)
        assertEquals("region_block", result?.matchSource)
        assertTrue(result?.description.orEmpty().contains("CA"))
        assertNull(RegionBlockChecker.decidePure("+12125550123", setOf("NY")))
    }

    @Test
    fun `priority preserves explicit blocks and caller name can override region`() {
        assertTrue(CheckerPriority.CALLER_NAME_TRUST < CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.CALLER_NAME_TRUST < CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.CALLER_NAME_TRUST < CheckerPriority.PREFIX_MATCH)
        assertTrue(CheckerPriority.CALLER_NAME_TRUST < CheckerPriority.WILDCARD_RULE)
        assertTrue(CheckerPriority.CALLER_NAME_TRUST < CheckerPriority.HASH_WILDCARD_RULE)
        assertTrue(CheckerPriority.CALLER_NAME_TRUST > CheckerPriority.REGION_BLOCK)
        assertTrue(CheckerPriority.REGION_BLOCK > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.REGION_BLOCK > CheckerPriority.HEURISTIC)
    }

    // ── Countries by calling code ─────────────────────────────────────

    @Test
    fun `countries are entered by calling code`() {
        assertEquals(
            linkedSetOf("NY", "+57", "+39", "+1809"),
            RegionRules.parseRegionCodes("NY, +57, +39, +1809"),
        )
    }

    @Test
    fun `Colombian number passes when +57 is allowed`() {
        assertFalse(RegionRules.isOutsideAllowedRegions("+573001234567", setOf("+57")))
    }

    @Test
    fun `Italian number passes when +39 is allowed`() {
        assertFalse(RegionRules.isOutsideAllowedRegions("+393381234567", setOf("+39")))
    }

    @Test
    fun `Colombian number blocked when only +39 is allowed`() {
        assertTrue(RegionRules.isOutsideAllowedRegions("+573001234567", setOf("+39")))
    }

    @Test
    fun `NANP and country codes coexist`() {
        val allowed = setOf("NY", "+57")
        assertFalse(RegionRules.isOutsideAllowedRegions("+12125550123", allowed))
        assertFalse(RegionRules.isOutsideAllowedRegions("+573001234567", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+393381234567", allowed))
    }

    @Test
    fun `two letter codes keep their state meaning where they are also ISO country codes`() {
        // CO, IN and PA are Colombia, India and Panama in ISO 3166.
        assertTrue(RegionRules.isOutsideAllowedRegions("+573001234567", setOf("CO")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+919876543210", setOf("IN")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+50761234567", setOf("PA")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+13035550123", setOf("CO")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+13175550123", setOf("IN")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+12155550123", setOf("PA")))
    }

    @Test
    fun `a calling code allows its country and not the state sharing its ISO code`() {
        assertFalse(RegionRules.isOutsideAllowedRegions("+573001234567", setOf("+57")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+919876543210", setOf("+91")))
        assertFalse(RegionRules.isOutsideAllowedRegions("+50761234567", setOf("+507")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+13035550123", setOf("+57")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+13175550123", setOf("+91")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+12155550123", setOf("+507")))
    }

    @Test
    fun `calling codes must be whole assigned codes`() {
        // 35x are three-digit codes, so +3 and +35 are not codes; +391 runs past Italy's +39.
        assertEquals(
            linkedSetOf("+353"),
            RegionRules.parseRegionCodes("+3, +35, +353, +391, +0, +1089, +18090, 57"),
        )
    }

    @Test
    fun `a NANP area code entry allows only that area code`() {
        // The Dominican Republic also uses 829 and 849; each area code is its own entry.
        val allowed = setOf("+1809")
        assertFalse(RegionRules.isOutsideAllowedRegions("+18095550123", allowed))
        assertFalse(RegionRules.isOutsideAllowedRegions("8095550123", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+18295550123", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+12125550123", allowed))
    }

    @Test
    fun `however an area code is typed after +1 it stays one area code`() {
        assertEquals(linkedSetOf("+1809", "NY"), RegionRules.parseRegionCodes("+1 809, NY"))
        assertEquals(linkedSetOf("+1809", "NY"), RegionRules.parseRegionCodes("+1 (809), NY"))
        assertEquals(linkedSetOf("+1809", "NY"), RegionRules.parseRegionCodes("+1-809, NY"))
        assertEquals(linkedSetOf("+1809", "NY"), RegionRules.parseRegionCodes("+1 (809) 555-0123, NY"))
    }

    @Test
    fun `nothing typed can leave a bare +1 that allows all of North America`() {
        assertEquals(linkedSetOf("NY"), RegionRules.parseRegionCodes("+1, NY"))
        assertEquals(linkedSetOf("NY"), RegionRules.parseRegionCodes("+1 8095550123, NY"))
        assertTrue(RegionRules.isOutsideAllowedRegions("+14165550123", RegionRules.parseRegionCodes("+1 8095550123, NY")))
        assertNull(RegionRules.normalizeDialingCode("+1"))
    }

    @Test
    fun `a bare number on a phone outside North America is read in that country`() {
        // Android leaves a number it can't validate in national form.
        assertFalse(RegionRules.isOutsideAllowedRegions("0612345678", setOf("+39"), homeRegionIso = "IT"))
        assertTrue(RegionRules.isOutsideAllowedRegions("0612345678", setOf("+44"), homeRegionIso = "IT"))
        // Ten digits shaped like a New York number are still an Italian number on an Italian phone.
        assertNull(RegionRules.regionCode("2125550123", homeRegionIso = "IT"))
        assertTrue(RegionRules.isOutsideAllowedRegions("2125550123", setOf("NY"), homeRegionIso = "IT"))
        assertTrue(RegionBlockChecker.decidePure("0612345678", setOf("+44"), homeRegionIso = "IT")?.shouldBlock == true)
        assertNull(RegionBlockChecker.decidePure("0612345678", setOf("+39"), homeRegionIso = "IT"))
    }

    @Test
    fun `a bare number dialed with the home international prefix is read as international`() {
        // "0044..." on an Italian phone is a UK number, so the user's +39 entry doesn't cover it.
        assertTrue(RegionRules.isOutsideAllowedRegions("00442079460000", setOf("+39"), homeRegionIso = "IT"))
        assertFalse(RegionRules.isOutsideAllowedRegions("00442079460000", setOf("+44"), homeRegionIso = "IT"))
        assertTrue(RegionBlockChecker.decidePure("00442079460000", setOf("+39"), homeRegionIso = "IT")?.shouldBlock == true)
        // Russia dials out with 810, Japan with 010 and North America with 011.
        assertTrue(RegionRules.isOutsideAllowedRegions("810442079460000", setOf("+7"), homeRegionIso = "RU"))
        assertTrue(RegionRules.isOutsideAllowedRegions("010442079460000", setOf("+81"), homeRegionIso = "JP"))
        assertFalse(RegionRules.isOutsideAllowedRegions("011442079460000", setOf("+44"), homeRegionIso = "US"))
        assertTrue(RegionRules.isOutsideAllowedRegions("011442079460000", setOf("NY"), homeRegionIso = "US"))
        // A national number with a leading trunk 0 stays at home.
        assertFalse(RegionRules.isOutsideAllowedRegions("0612345678", setOf("+39"), homeRegionIso = "IT"))
        assertFalse(RegionRules.isOutsideAllowedRegions("0312345678", setOf("+81"), homeRegionIso = "JP"))
    }

    @Test
    fun `bare numbers read the way libphonenumber reads them`() {
        val uk = RegionCallingCodes.BareNumber.International("442079460000")
        assertEquals(uk, RegionCallingCodes.readBareNumber("00442079460000", "it"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("0011442079460000", "AU"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("011442079460000", null))
        // The Marshall Islands dial out with 011 without being on +1.
        assertEquals(uk, RegionCallingCodes.readBareNumber("011442079460000", "MH"))
        // Where the own prefix is 011, 010 or 810, the ITU 00 still reads as international.
        assertEquals(uk, RegionCallingCodes.readBareNumber("00442079460000", "US"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("00442079460000", "JP"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("00442079460000", "RU"))
        assertEquals(RegionCallingCodes.BareNumber.National, RegionCallingCodes.readBareNumber("0612345678", "IT"))
        // A calling code never starts with 0, and a prefix alone is no number.
        assertEquals(RegionCallingCodes.BareNumber.Unreadable, RegionCallingCodes.readBareNumber("000123", "IT"))
        assertEquals(RegionCallingCodes.BareNumber.Unreadable, RegionCallingCodes.readBareNumber("00", "IT"))
    }

    @Test
    fun `domestic numbers that start with 00 stay domestic`() {
        // Toll-free ranges libphonenumber lists as national numbers.
        listOf(
            "BG" to "008001234567",
            "PA" to "00800123456",
            "QA" to "00800123456",
            "TH" to "0018001234567",
            "IN" to "0008001234567",
            "JP" to "0037123456",
            "UY" to "000412345",
        ).forEach { (region, number) ->
            assertEquals("$region $number", RegionCallingCodes.BareNumber.National, RegionCallingCodes.readBareNumber(number, region))
        }
        assertFalse(RegionRules.isOutsideAllowedRegions("008001234567", setOf("+359"), homeRegionIso = "BG"))
        assertFalse(RegionRules.isOutsideAllowedRegions("0018001234567", setOf("+66"), homeRegionIso = "TH"))
    }

    @Test
    fun `domestic numbers that start like an international prefix stay domestic`() {
        val national = RegionCallingCodes.BareNumber.National
        // Tajik mobiles from 00 (TJ dials out with 810, so 00 is only the ITU fallback).
        assertEquals(national, RegionCallingCodes.readBareNumber("004104761", "TJ"))
        assertEquals(national, RegionCallingCodes.readBareNumber("000845221", "TJ"))
        // Chilean toll-free 1230 0xx xxxx, which begins with Chile's own prefix 1230 and a 0.
        assertEquals(national, RegionCallingCodes.readBareNumber("12300201234", "CL"))
        // Belarusian premium numbers from 810, Belarus's own prefix.
        assertEquals(national, RegionCallingCodes.readBareNumber("8101234567", "BY"))
        assertFalse(RegionRules.isOutsideAllowedRegions("004104761", setOf("+992"), homeRegionIso = "TJ"))
        assertFalse(RegionRules.isOutsideAllowedRegions("12300201234", setOf("+56"), homeRegionIso = "CL"))
        // Dialing out still reads as international in those regions.
        val uk = RegionCallingCodes.BareNumber.International("442079460000")
        assertEquals(uk, RegionCallingCodes.readBareNumber("00442079460000", "TJ"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("810442079460000", "TJ"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("1230442079460000", "CL"))
        assertEquals(uk, RegionCallingCodes.readBareNumber("810442079460000", "BY"))
    }

    @Test
    fun `a bare number that can't be read never passes as the home country`() {
        // "000..." on an Italian phone is neither Italian nor international.
        assertTrue(RegionRules.isOutsideAllowedRegions("00012345678", setOf("+39"), homeRegionIso = "IT"))
        // Japan dials out with 010, but a caller ID written with 00 is still foreign.
        assertTrue(RegionRules.isOutsideAllowedRegions("00442079460000", setOf("+81"), homeRegionIso = "JP"))
        assertFalse(RegionRules.isOutsideAllowedRegions("00442079460000", setOf("+44"), homeRegionIso = "JP"))
    }

    @Test
    fun `a bare number is NANP on a phone that uses +1 or has no known region`() {
        assertEquals("NY", RegionRules.regionCode("2125550123", homeRegionIso = "US"))
        assertEquals("NY", RegionRules.regionCode("2125550123", homeRegionIso = "DO"))
        assertEquals("NY", RegionRules.regionCode("2125550123"))
        assertFalse(RegionRules.isOutsideAllowedRegions("8095550123", setOf("+1809"), homeRegionIso = "DO"))
        assertFalse(RegionRules.isOutsideAllowedRegions("2125550123", setOf("NY"), homeRegionIso = "ZZ"))
    }

    @Test
    fun `calling code table covers the NANP and reads regions case-insensitively`() {
        listOf("US", "CA", "PR", "DO", "JM").forEach { assertEquals(it, "1", RegionCallingCodes.forRegion(it)) }
        assertEquals("39", RegionCallingCodes.forRegion("it"))
        assertEquals("44", RegionCallingCodes.forRegion("GB"))
        assertNull(RegionCallingCodes.forRegion("ZZ"))
        assertNull(RegionCallingCodes.forRegion(null))
    }

    @Test
    fun `ten digit international numbers are not read as NANP area codes`() {
        // Penang (+60 4) and Hamilton, New Zealand (+64 7) have ten digits after
        // the plus, which the area-code table reads as Vancouver 604 and Toronto 647.
        assertNull(RegionRules.regionCode("+6041234567"))
        assertNull(RegionRules.regionCode("+6471234567"))
        assertTrue(RegionRules.isOutsideAllowedRegions("+6041234567", setOf("BC")))
        assertTrue(RegionRules.isOutsideAllowedRegions("+6471234567", setOf("ON")))
        assertEquals("BC", RegionRules.regionCode("6045550123"))
    }

    @Test
    fun `countries sharing plus one are differentiated by area code`() {
        val allowed = setOf("NY")
        assertFalse(RegionRules.isOutsideAllowedRegions("+12125550123", allowed))
        assertTrue(RegionRules.isOutsideAllowedRegions("+14165550123", allowed))
    }

    @Test
    fun `caller name block stays below every allow and near the bottom of detection`() {
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.MANUAL_WHITELIST)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.CONTACT_WHITELIST)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.TEMPORARY_ALLOW)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.RECENTLY_DIALED)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.EMERGENCY_CALLBACK)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.CALLER_NAME_TRUST)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK < CheckerPriority.CAMPAIGN_BURST)
        assertTrue(CheckerPriority.CALLER_NAME_BLOCK > CheckerPriority.ML_SCORER)
    }
}
