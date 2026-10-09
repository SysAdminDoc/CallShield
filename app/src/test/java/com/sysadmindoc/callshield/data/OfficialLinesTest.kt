package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialLinesTest {
    @Test
    fun `a published line matches in every form a call or a lookup carries`() {
        for (form in listOf("+18002752273", "18002752273", "8002752273", "(800) 275-2273", "+1 800-275-2273")) {
            assertEquals(form, "Apple Support", OfficialLines.organization(form))
        }
    }

    @Test
    fun `the same digits in another country or a near miss match nothing`() {
        assertNull(OfficialLines.organization("+448002752273"))
        assertNull(OfficialLines.organization("+8002752273"))
        assertNull(OfficialLines.organization("8002752274"))
        assertNull(OfficialLines.organization("28002752273"))
        assertNull(OfficialLines.organization(""))
    }
}
