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

    @Test
    fun `a prompt closed without an answer the first time leaves the user where they were`() {
        val denied = setOf(Manifest.permission.READ_CALL_LOG)

        assertFalse(opensAppInfoAfterRequest(denied, promptShown = true, refusedBefore = emptySet()) { false })
    }

    @Test
    fun `a second refusal on a prompt that was shown opens App info`() {
        val denied = setOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS)

        assertTrue(opensAppInfoAfterRequest(denied, promptShown = true, refusedBefore = setOf(Manifest.permission.READ_SMS)) { false })
    }

    @Test
    fun `a request that came straight back refused opens App info whatever came before`() {
        val denied = setOf(Manifest.permission.READ_CALL_LOG)

        assertTrue(opensAppInfoAfterRequest(denied, promptShown = false, refusedBefore = emptySet()) { false })
    }

    @Test
    fun `a role request that came straight back refused opens Default apps`() {
        assertTrue(opensDefaultAppsAfterRoleRequest(roleHeld = false, elapsedMillis = 120))
    }

    @Test
    fun `a role refused on a prompt that was shown stays put`() {
        assertFalse(opensDefaultAppsAfterRoleRequest(roleHeld = false, elapsedMillis = PROMPT_MIN_MILLIS))
        assertFalse(opensDefaultAppsAfterRoleRequest(roleHeld = false, elapsedMillis = 4_000))
    }

    @Test
    fun `a role handed over never opens Default apps, however fast`() {
        assertFalse(opensDefaultAppsAfterRoleRequest(roleHeld = true, elapsedMillis = 50))
    }
}
