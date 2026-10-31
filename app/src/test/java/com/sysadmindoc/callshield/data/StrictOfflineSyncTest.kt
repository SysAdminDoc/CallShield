package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.StrictOffline
import com.sysadmindoc.callshield.data.remote.StrictOfflineException
import com.sysadmindoc.callshield.data.repository.FeedMirrorSave
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With strict offline mode on, the screens that fetch say so instead of
 * reporting a failure: a manual sync, a list download and a mirror address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StrictOfflineSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = CountingFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed)

    @After
    fun tearDown() {
        StrictOffline.resetForTest()
        fixture.close()
    }

    @Test
    fun `a manual sync says offline mode is on instead of failing or loading the bundled snapshot`() {
        StrictOffline.set(true)

        val result = runBlocking { fixture.repository.syncFromGitHub(force = true) }

        assertTrue("not a failed sync", result.success)
        assertTrue(result.warning)
        assertTrue(result.offline)
        assertFalse(result.shouldRetry)
        assertEquals(context.getString(R.string.sync_strict_offline), result.message)
        assertEquals("the bundled snapshot was not loaded over the data on the phone", 0, runBlocking { fixture.dao.getSpamCount() })
    }

    @Test
    fun `adding a list says offline mode is on and records nothing`() {
        StrictOffline.set(true)
        feed.failure = StrictOfflineException()

        val result = runBlocking { fixture.repository.applyExternalBlocklistSubscription("https://lists.example.test/daily.txt", "Daily list") }

        assertFalse(result.success)
        assertTrue(result.offline)
        assertEquals(context.getString(R.string.strict_offline_lists), result.message)
        assertEquals(emptyList<Any>(), runBlocking { fixture.repository.externalBlocklistSubscriptions.first() })
    }

    @Test
    fun `due lists are left alone and no attempt is recorded`() {
        StrictOffline.set(true)

        val outcomes = runBlocking { fixture.repository.refreshDueExternalBlocklists() }

        assertEquals(emptyList<Any>(), outcomes)
        assertEquals(0, feed.fetches)
    }

    @Test
    fun `a mirror address is not checked over the network`() {
        StrictOffline.set(true)

        assertEquals(FeedMirrorSave.OFFLINE, runBlocking { fixture.repository.saveFeedMirrorUrl("https://mirror.example/callshield/") })
    }

    @Test
    fun `the switch is stored, read back and followed`() {
        assertFalse("off by default", runBlocking { fixture.repository.isStrictOffline() })

        runBlocking { fixture.repository.setStrictOffline(true) }

        assertTrue(runBlocking { fixture.repository.strictOfflineEnabled.first() })
        assertTrue(runBlocking { fixture.repository.isStrictOffline() })
    }

    private class CountingFeed : ExternalBlocklistDataSource {
        var failure: Exception? = null
        var fetches = 0

        override suspend fun fetchText(url: String): Result<String> {
            fetches++
            return Result.failure(failure ?: IllegalStateException("no feed scripted"))
        }
    }
}
