package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `newer release is offered`() {
        assertEquals(AppUpdateStatus.UPDATE_AVAILABLE, AppUpdateChecker.evaluate("1.7.33", "v1.8.0"))
    }

    @Test
    fun `same release is current`() {
        assertEquals(AppUpdateStatus.UP_TO_DATE, AppUpdateChecker.evaluate("1.7.33", "1.7.33"))
    }

    @Test
    fun `installed version ahead is not downgraded`() {
        assertEquals(AppUpdateStatus.INSTALLED_NEWER, AppUpdateChecker.evaluate("1.8.0", "v1.7.33"))
    }

    @Test
    fun `malformed release tags are rejected`() {
        assertEquals(AppUpdateStatus.MALFORMED_RELEASE, AppUpdateChecker.evaluate("1.7.33", "release-1.8.0"))
        assertEquals(AppUpdateStatus.MALFORMED_RELEASE, AppUpdateChecker.evaluate("debug", "1.8.0"))
    }

    @Test
    fun `state preserves release links and update availability`() {
        val state =
            AppUpdateState.fromRelease(
                currentVersion = "1.7.33",
                release =
                    AppUpdateRelease(
                        tagName = "v1.8.0",
                        htmlUrl = "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.8.0",
                        checksumUrl = "https://github.com/SysAdminDoc/CallShield/releases/download/v1.8.0/CallShield.apk.sha256",
                    ),
                checkedAt = 123L,
            )

        assertTrue(state.updateAvailable)
        assertEquals("v1.8.0", state.latestTag)
        assertEquals(123L, state.checkedAt)
        assertFalse(state.releaseUrl.isNullOrBlank())
        assertFalse(state.checksumUrl.isNullOrBlank())
    }
}
