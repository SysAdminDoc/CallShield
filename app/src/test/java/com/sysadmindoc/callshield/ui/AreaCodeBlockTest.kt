package com.sysadmindoc.callshield.ui

import com.sysadmindoc.callshield.data.model.LogAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AreaCodeBlockTest {
    @Test
    fun `a +1 number blocks its area code under +1`() {
        assertEquals(AreaCodeBlock("415", "+1415*"), areaCodeBlock("+14155550123", homeRegionIso = "US"))
        // A +1 number is North American wherever the phone is.
        assertEquals(AreaCodeBlock("415", "+1415*"), areaCodeBlock("+14155550123", homeRegionIso = "FR"))
    }

    @Test
    fun `a +33 mobile offers no block rather than a +1 one`() {
        assertNull(areaCodeBlock("+33612345678", homeRegionIso = "FR"))
        assertNull(areaCodeBlock("+33612345678", homeRegionIso = "US"))
    }

    @Test
    fun `an international number blocks its own area code under its own calling code`() {
        val paris = areaCodeBlock("+33 1 45 23 00 01", homeRegionIso = "FR")
        assertEquals(AreaCodeBlock("1", "+331*", "33"), paris)
        assertEquals("+33 1", paris?.label)
        assertEquals(AreaCodeBlock("30", "+4930*", "49"), areaCodeBlock("+49301234567", homeRegionIso = "DE"))
        assertEquals(AreaCodeBlock("20", "+4420*", "44"), areaCodeBlock("+442079460018", homeRegionIso = "US"))
        // Italy keeps the 0 in international form.
        assertEquals(AreaCodeBlock("06", "+3906*", "39"), areaCodeBlock("+390612345678", homeRegionIso = "IT"))
    }

    @Test
    fun `the longest area code wins where a short one holds longer ones`() {
        // Brandenburg an der Havel is 3381 and Gross Glienicke 33201, both under 3.
        assertEquals("3381", areaCodeBlock("+493381123456", homeRegionIso = "DE")?.areaCode)
        assertEquals("33201", areaCodeBlock("+4933201123456", homeRegionIso = "DE")?.areaCode)
    }

    @Test
    fun `a country without area codes offers nothing`() {
        assertNull(areaCodeBlock("+34912345678", homeRegionIso = "ES"))
        // A bare number outside North America isn't read for an area code.
        assertNull(areaCodeBlock("0145230001", homeRegionIso = "FR"))
    }

    @Test
    fun `North American labels stay bare`() {
        assertEquals("415", areaCodeBlock("+14155550123", homeRegionIso = "US")?.label)
    }

    @Test
    fun `counts stay North American even when a short +33 number fits the length filter`() {
        // Ten characters like a bare NANP number, but it's Paris.
        val aggregates = listOf(LogAggregate("+331234567", 9), LogAggregate("+14155550123", 5))

        assertEquals(listOf(AreaCodeBlock("415", "+1415*") to 5), countsByAreaCode(aggregates, homeRegionIso = "FR"))
    }

    @Test
    fun `a bare ten digit number reads by the home region`() {
        assertEquals(AreaCodeBlock("415", "+1415*"), areaCodeBlock("4155550123", homeRegionIso = "US"))
        assertEquals(AreaCodeBlock("415", "+1415*"), areaCodeBlock("4155550123", homeRegionIso = null))
        assertNull(areaCodeBlock("0612345678", homeRegionIso = "FR"))
        assertNull(areaCodeBlock("9845012345", homeRegionIso = "IN"))
    }

    @Test
    fun `counts merge a +1 prefix with its bare spelling on a North American phone`() {
        val aggregates =
            listOf(
                LogAggregate("+14155550123", 4),
                LogAggregate("4155550199", 3),
                LogAggregate("+12125550100", 5),
            )

        assertEquals(
            listOf(AreaCodeBlock("415", "+1415*") to 7, AreaCodeBlock("212", "+1212*") to 5),
            countsByAreaCode(aggregates, homeRegionIso = "US"),
        )
    }

    @Test
    fun `counts drop bare numbers elsewhere but keep +1 ones`() {
        val aggregates =
            listOf(
                LogAggregate("0612345678", 9),
                LogAggregate("+14155550123", 5),
                LogAggregate("4155550199", 3),
            )

        assertEquals(
            listOf(AreaCodeBlock("415", "+1415*") to 5),
            countsByAreaCode(aggregates, homeRegionIso = "FR"),
        )
    }

    @Test
    fun `counts tie on area code order`() {
        val aggregates = listOf(LogAggregate("+19175550100", 5), LogAggregate("+12125550100", 5))

        assertEquals(listOf("212", "917"), countsByAreaCode(aggregates, homeRegionIso = "US").map { it.first.areaCode })
    }
}
