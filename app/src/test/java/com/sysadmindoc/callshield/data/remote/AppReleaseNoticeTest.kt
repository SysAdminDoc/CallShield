package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.AppReleaseNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The release notice a phone reads on its sync, and when Home offers it. */
class AppReleaseNoticeTest {
    private val sha = "c12698b91ce9ce03c9aff9f4db1486b0f75fcfc3a22371a6ed90ec00c6cb9892"

    private fun body(
        code: Any = 71,
        name: String = "1.11.0",
        url: String = "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.11.0",
        hash: String = sha,
    ) = """{"version_code": $code, "version_name": "$name", "release_url": "$url", "apk_sha256": "$hash"}"""

    @Test
    fun `a well-formed notice is read`() {
        assertEquals(
            AppReleaseNotice(71, "1.11.0", "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.11.0", sha),
            GitHubDataSource.parseAppReleaseNotice(body()),
        )
    }

    @Test
    fun `a notice can only link to its own tag page`() {
        assertRefused(body(url = "https://evil.example/CallShield/releases/tag/v1.11.0"))
        assertRefused(body(url = "https://github.com/someone/CallShield/releases/tag/v1.11.0"))
        assertRefused(body(url = "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.10.0"))
    }

    @Test
    fun `unusable fields and non-JSON are refused`() {
        assertRefused(body(code = 0))
        assertRefused(body(name = "1.11"))
        assertRefused(body(hash = sha.uppercase()))
        assertRefused(body(hash = "abc"))
        assertRefused("""{"version_name": "1.11.0"}""")
        assertRefused("not json")
    }

    @Test
    fun `Home offers only a newer release the user hasn't dismissed`() {
        val notice = AppReleaseNotice(71, "1.11.0", "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.11.0", sha)
        assertTrue(notice.shouldShow(installedCode = 70, dismissedCode = 0))
        assertFalse(notice.shouldShow(installedCode = 71, dismissedCode = 0))
        assertFalse(notice.shouldShow(installedCode = 72, dismissedCode = 0))
        assertFalse(notice.shouldShow(installedCode = 70, dismissedCode = 71))
        // Dismissing one release doesn't hide the next.
        assertTrue(notice.copy(versionCode = 72).shouldShow(installedCode = 70, dismissedCode = 71))
    }

    private fun assertRefused(body: String) {
        try {
            GitHubDataSource.parseAppReleaseNotice(body)
            fail("accepted $body")
        } catch (_: GitHubFeedValidationException) {
            // Refused, as it should be.
        }
    }
}
