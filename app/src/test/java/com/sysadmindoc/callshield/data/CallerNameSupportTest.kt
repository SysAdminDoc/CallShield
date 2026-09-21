package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerNameSupportTest {
    @Test
    fun `fewer than 20 screened calls is unknown`() {
        assertEquals(CallerNameSupport.UNKNOWN, derive(withName = 0, withoutName = 0))
        assertEquals(CallerNameSupport.UNKNOWN, derive(withName = 0, withoutName = 19))
        assertEquals(CallerNameSupport.UNKNOWN, derive(withName = 5, withoutName = 5))
    }

    @Test
    fun `20 or more calls with none carrying a name means not provided`() {
        assertEquals(CallerNameSupport.NOT_PROVIDED, derive(withName = 0, withoutName = 20))
        assertEquals(CallerNameSupport.NOT_PROVIDED, derive(withName = 0, withoutName = 100))
    }

    @Test
    fun `at least one name in 20 or more calls means provided`() {
        assertEquals(CallerNameSupport.PROVIDED, derive(withName = 1, withoutName = 19))
        assertEquals(CallerNameSupport.PROVIDED, derive(withName = 10, withoutName = 40))
    }

    private fun derive(withName: Int, withoutName: Int): CallerNameSupport {
        val total = withName + withoutName
        return when {
            total < SpamRepository.CNAP_OBSERVATION_THRESHOLD -> CallerNameSupport.UNKNOWN
            withName > 0 -> CallerNameSupport.PROVIDED
            else -> CallerNameSupport.NOT_PROVIDED
        }
    }
}
