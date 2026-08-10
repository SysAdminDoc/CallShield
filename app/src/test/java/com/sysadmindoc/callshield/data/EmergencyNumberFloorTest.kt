package com.sysadmindoc.callshield.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNumberFloorTest {
    @Test
    fun `recognized emergency and public safety codes are protected`() {
        listOf("000", "111", "112", "118", "119", "911", "999", "+1 (911)").forEach {
            assertTrue("$it should be protected", EmergencyNumberFloor.isProtected(it))
        }
    }

    @Test
    fun `numbers that merely contain an emergency code are not protected`() {
        listOf("411", "555911", "+1212555911", "9110", "9911").forEach {
            assertFalse("$it should not be protected", EmergencyNumberFloor.isProtected(it))
        }
    }
}
