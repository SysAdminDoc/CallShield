package com.sysadmindoc.callshield.ui

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRecoveryTest {
    @Test
    fun `denied with no dialog left after a request opens App info`() {
        assertTrue(opensAppInfoAfterRequest(setOf(Manifest.permission.READ_CALL_LOG)) { false })
    }

    @Test
    fun `denied once with the dialog still available stays put`() {
        assertFalse(opensAppInfoAfterRequest(setOf(Manifest.permission.READ_CALL_LOG)) { true })
    }

    @Test
    fun `everything granted never opens App info`() {
        assertFalse(opensAppInfoAfterRequest(emptySet()) { false })
    }

    @Test
    fun `any refusal Android can still ask about keeps the dialog route`() {
        val askable = Manifest.permission.READ_SMS
        val denied = setOf(Manifest.permission.READ_CALL_LOG, askable)

        assertFalse(opensAppInfoAfterRequest(denied) { permission -> permission == askable })
    }

    @Test
    fun `several refusals with no dialog left open App info`() {
        val denied = setOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS)

        assertTrue(opensAppInfoAfterRequest(denied) { false })
    }

    @Test
    fun `a refused extra doesn't count when the needed permissions were granted`() {
        val grants = mapOf(Manifest.permission.READ_CALL_LOG to true, Manifest.permission.READ_PHONE_STATE to false)

        assertEquals(emptySet<String>(), refusalsThatCount(grants, appInfoFor = listOf(Manifest.permission.READ_CALL_LOG)))
        assertEquals(setOf(Manifest.permission.READ_PHONE_STATE), refusalsThatCount(grants, appInfoFor = null))
    }
}
