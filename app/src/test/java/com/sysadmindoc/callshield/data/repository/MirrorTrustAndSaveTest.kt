package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedSnapshot
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.service.HotDataSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A mirror covering for GitHub mustn't hide that GitHub's certificate pins
 * are failing, since only an app update fixes those, and a mirror is saved
 * only once it has served this project's signed manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MirrorTrustAndSaveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val remote = MirrorFedRemote()
    private val fixture = IsolatedRepositoryFixture(context, remote = remote)
    private val repository = fixture.repository

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a database the mirror served while GitHub's pins failed still records the failure`() {
        remote.gitHubTrustFailing = true

        runBlocking { repository.syncFromGitHub(force = true) }

        assertTrue(runBlocking { repository.readFeedTrustFailedAt() } > 0L)
    }

    @Test
    fun `a database GitHub served itself clears the record`() {
        runBlocking { repository.recordFeedTrust(failed = true) }

        runBlocking { repository.syncFromGitHub(force = true) }

        assertEquals(0L, runBlocking { repository.readFeedTrustFailedAt() })
    }

    @Test
    fun `trending feeds the mirror served while GitHub's pins failed still record the failure`() {
        runBlocking { HotDataSync.refresh(context, MirrorFedFeeds(gitHubTrustFailing = true), repository, fixture.dao) }

        assertTrue(runBlocking { repository.readFeedTrustFailedAt() } > 0L)
    }

    @Test
    fun `a mirror is saved only once it serves the signed manifest`() {
        remote.mirrorServes = false
        assertEquals(FeedMirrorSave.UNVERIFIED, runBlocking { repository.saveFeedMirrorUrl(MIRROR) })
        assertNull(runBlocking { repository.feedMirrorUrl.first() })

        assertEquals(FeedMirrorSave.INVALID, runBlocking { repository.saveFeedMirrorUrl("http://mirror.example.test/") })

        remote.mirrorServes = true
        assertEquals(FeedMirrorSave.SAVED, runBlocking { repository.saveFeedMirrorUrl(MIRROR) })
        assertEquals(MIRROR, runBlocking { repository.feedMirrorUrl.first() })
    }

    private class MirrorFedRemote : SpamDataSource {
        override var gitHubTrustFailing = false
        var mirrorServes = true

        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ) = Result.success(SpamDatabase(version = 2, updated = "2026-09-21", numbers = emptyList(), prefixes = emptyList()))

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> = Result.failure(IOException("offline"))

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.failure(UnsupportedOperationException())

        override suspend fun probeMirror(baseUrl: String): Result<Unit> = if (mirrorServes) Result.success(Unit) else Result.failure(IOException("the mirror answered 404"))
    }

    private class MirrorFedFeeds(
        override val gitHubTrustFailing: Boolean,
    ) : HotFeedDataSource {
        private val stamp = "2026-09-21T10:00:00+00:00"
        private val numbers = listOf(HotNumber(number = "+12125550101", type = "robocall", description = ""))

        override suspend fun fetchHotList(
            owner: String,
            repo: String,
        ) = Result.success(numbers)

        override suspend fun fetchHotRanges(
            owner: String,
            repo: String,
        ) = Result.success(emptyList<String>())

        override suspend fun fetchSpamDomains(
            owner: String,
            repo: String,
        ) = Result.success(emptyList<String>())

        override suspend fun fetchHotListSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(HotFeedSnapshot(numbers, generatedAt = stamp))

        override suspend fun fetchHotRangesSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(HotFeedSnapshot(emptyList<String>(), generatedAt = stamp))

        override suspend fun fetchSpamDomainsSnapshot(
            owner: String,
            repo: String,
        ) = Result.success(HotFeedSnapshot(emptyList<String>(), generatedAt = stamp))

        override fun parseHotListJson(body: String): List<HotNumber> = emptyList()

        override fun parseHotRangesJson(body: String): List<String> = emptyList()

        override fun parseSpamDomainsJson(body: String): List<String> = emptyList()
    }

    private companion object {
        const val MIRROR = "https://mirror.example.test/callshield/"
    }
}
