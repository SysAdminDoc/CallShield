package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.data.model.AppReleaseNotice
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppReleaseNoticeRepositoryTest {
    private var served: Result<AppReleaseNotice> = Result.failure(IOException("offline"))
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        val remote =
            object : SpamDataSource by GitHubDataSource() {
                override suspend fun fetchAppReleaseNotice(
                    owner: String,
                    repo: String,
                ) = served
            }
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext(), remote = remote)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a newer release shows after a sync, hides when dismissed and comes back for the next one`() =
        runBlocking {
            val repo = fixture.repository
            assertNull(repo.appReleaseNotice.first())

            val newer = notice(BuildConfig.VERSION_CODE + 1)
            served = Result.success(newer)
            assertTrue(repo.refreshAppReleaseNotice())
            assertEquals(newer, repo.appReleaseNotice.first())

            repo.dismissAppReleaseNotice(newer.versionCode)
            assertNull(repo.appReleaseNotice.first())

            val next = notice(BuildConfig.VERSION_CODE + 2)
            served = Result.success(next)
            repo.refreshAppReleaseNotice()
            assertEquals(next, repo.appReleaseNotice.first())
        }

    @Test
    fun `a refused or missing file keeps the last good notice, and a current build shows none`() =
        runBlocking {
            val repo = fixture.repository
            val newer = notice(BuildConfig.VERSION_CODE + 1)
            served = Result.success(newer)
            repo.refreshAppReleaseNotice()

            served = Result.failure(IOException("signature doesn't verify"))
            assertFalse(repo.refreshAppReleaseNotice())
            assertEquals(newer, repo.appReleaseNotice.first())

            served = Result.success(notice(BuildConfig.VERSION_CODE))
            repo.refreshAppReleaseNotice()
            assertNull(repo.appReleaseNotice.first())
        }

    private fun notice(code: Int) =
        AppReleaseNotice(
            versionCode = code,
            versionName = "9.9.$code",
            releaseUrl = "https://github.com/SysAdminDoc/CallShield/releases/tag/v9.9.$code",
            apkSha256 = "ab".repeat(32),
        )
}
