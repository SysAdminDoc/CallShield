package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.checker.CallerNameBlockChecker
import com.sysadmindoc.callshield.data.checker.CallerNameTrustChecker
import com.sysadmindoc.callshield.data.checker.CheckerPriority
import com.sysadmindoc.callshield.data.checker.RegionBlockChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
