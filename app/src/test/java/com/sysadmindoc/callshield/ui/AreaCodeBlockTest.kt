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
    fun `a +33 number offers no block rather than a +1 one`() {
        assertNull(areaCodeBlock("+33612345678", homeRegionIso = "FR"))
        assertNull(areaCodeBlock("+33612345678", homeRegionIso = "US"))
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
