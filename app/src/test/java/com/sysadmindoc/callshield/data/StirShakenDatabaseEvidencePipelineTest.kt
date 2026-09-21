package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedSnapshot
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.service.HotDataSync
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The whole call pipeline, for a caller the carrier verified (PASSED) whose
 * number is in the downloaded database. The trust allow ranks above the
 * database to protect the real owners of numbers spoofed in old complaint
 * data, so it must win against stale rows and lose against current ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StirShakenDatabaseEvidencePipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)
    private val today = LocalDate.now(ZoneOffset.UTC)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a verified call from a number reported this year is still blocked`() {
        databaseRow("+12125550140", lastSeen = today.minusDays(30))

        val result = callFrom("+12125550140", verificationStatus = PASSED)

        assertTrue(result.isSpam)
        assertEquals("database", result.matchSource)
    }

    @Test
    fun `a verified call from a number last reported years ago rings through`() {
        databaseRow("+12125550141", lastSeen = today.minusDays(900))

        val result = callFrom("+12125550141", verificationStatus = PASSED)

        assertFalse(result.isSpam)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test
    fun `the same stale row still blocks a call the carrier did not verify`() {
        databaseRow("+12125550142", lastSeen = today.minusDays(900))

        val result = callFrom("+12125550142", verificationStatus = NOT_VERIFIED)

        assertTrue(result.isSpam)
        assertEquals("database", result.matchSource)
    }

    @Test
    fun `a verified call from a stale database number that is trending now is blocked`() {
        databaseRow("+12125550144", lastSeen = today.minusDays(900))
        runBlocking {
            fixture.repository.replaceHotList(
                listOf(SpamNumber(number = "+12125550144", type = "robocall", source = "hot_list")),
            )
        }

        val result = callFrom("+12125550144", verificationStatus = PASSED)

        assertTrue(result.isSpam)
        assertEquals("database", result.matchSource)
    }

    @Test
    fun `once it stops trending the same stale row rings through again`() {
        databaseRow("+12125550145", lastSeen = today.minusDays(900))
        runBlocking {
            fixture.repository.replaceHotList(
                listOf(SpamNumber(number = "+12125550145", type = "robocall", source = "hot_list")),
            )
            fixture.repository.replaceHotList(emptyList())
        }

        val result = callFrom("+12125550145", verificationStatus = PASSED)

        assertFalse(result.isSpam)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test
    fun `a restart keeps a trending set whose numbers all have database rows`() {
        // Such a hot list stores no rows of its own, which used to read as
        // "never synced" and let the build-time snapshot replace it.
        databaseRow("+12125550146", lastSeen = today.minusDays(900))
        val feeds = TrendingFeeds(listOf("+12125550146"), bundled = listOf("+12125550149"))

        runBlocking {
            HotDataSync.refresh(context, feeds, fixture.repository, fixture.dao)
            HotDataSync.primeBundled(context, feeds, fixture.repository, fixture.dao)
        }

        assertTrue(callFrom("+12125550146", verificationStatus = PASSED).isSpam)
        assertEquals(emptySet<String>(), hotRows())
    }

    @Test
    fun `a failed refresh doesn't swap the trending list for the build-time one`() {
        databaseRow("+12125550147", lastSeen = today.minusDays(900))
        val feeds = TrendingFeeds(listOf("+12125550147"), bundled = listOf("+12125550149"))
        runBlocking { HotDataSync.refresh(context, feeds, fixture.repository, fixture.dao) }

        feeds.offline = true
        runBlocking { HotDataSync.refresh(context, feeds, fixture.repository, fixture.dao) }

        assertTrue(callFrom("+12125550147", verificationStatus = PASSED).isSpam)
        assertEquals(emptySet<String>(), hotRows())
    }

    @Test
    fun `the build-time snapshot never counts as trending`() {
        databaseRow("+12125550148", lastSeen = today.minusDays(900))
        val feeds = TrendingFeeds(emptyList(), bundled = listOf("+12125550148"))

        runBlocking { HotDataSync.primeBundled(context, feeds, fixture.repository, fixture.dao) }

        val result = callFrom("+12125550148", verificationStatus = PASSED)
        assertFalse(result.isSpam)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test
    fun `a trending mark older than the hot rows' lifetime no longer blocks`() {
        databaseRow("+12125550150", lastSeen = today.minusDays(900))
        runBlocking {
            fixture.repository.replaceHotList(
                listOf(SpamNumber(number = "+12125550150", type = "robocall", source = "hot_list")),
                appliedAt = System.currentTimeMillis() - SpamRepository.HOT_ROW_TTL_MS - 60_000L,
            )
        }

        val result = callFrom("+12125550150", verificationStatus = PASSED)
        assertFalse(result.isSpam)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test
    fun `an explicit user block still beats a verified call`() {
        databaseRow("+12125550143", lastSeen = today.minusDays(900), isUserBlocked = true)

        val result = callFrom("+12125550143", verificationStatus = PASSED)

        assertTrue(result.isSpam)
        assertEquals("user_blocklist", result.matchSource)
    }

    private fun databaseRow(
        number: String,
        lastSeen: LocalDate,
        isUserBlocked: Boolean = false,
    ) = runBlocking {
        fixture.dao.insertNumber(
            SpamNumber(
                number = number,
                type = "robocall",
                reports = 3,
                lastSeen = lastSeen.toString(),
                source = "github",
                isUserBlocked = isUserBlocked,
            ),
        )
    }

    private fun hotRows(): Set<String> =
        runBlocking {
            fixture.dao
                .getNumbersBySource("hot_list")
                .map { it.number }
                .toSet()
        }

    /**
     * A network hot list of [numbers]. The build-time snapshot parses to
     * [bundled], standing in for whatever the shipped asset holds.
     */
    private class TrendingFeeds(
        private val numbers: List<String>,
        private val bundled: List<String>,
    ) : HotFeedDataSource {
        var offline = false
        private val stamp = "2026-09-21T10:00:00+00:00"

        private fun hot(list: List<String>) = list.map { HotNumber(number = it, type = "robocall", description = "") }

        private fun <T> online(value: T): Result<T> = if (offline) Result.failure(IOException("offline")) else Result.success(value)

        override suspend fun fetchHotList(
            owner: String,
            repo: String,
        ) = online(hot(numbers))

        override suspend fun fetchHotRanges(
            owner: String,
            repo: String,
        ) = online(emptyList<String>())

        override suspend fun fetchSpamDomains(
            owner: String,
            repo: String,
        ) = online(emptyList<String>())

        override suspend fun fetchHotListSnapshot(
            owner: String,
            repo: String,
        ) = online(HotFeedSnapshot(hot(numbers), generatedAt = stamp))

        override suspend fun fetchHotRangesSnapshot(
            owner: String,
            repo: String,
        ) = online(HotFeedSnapshot(emptyList<String>(), generatedAt = stamp))

        override suspend fun fetchSpamDomainsSnapshot(
            owner: String,
            repo: String,
        ) = online(HotFeedSnapshot(emptyList<String>(), generatedAt = stamp))

        override fun parseHotListJson(body: String): List<HotNumber> = hot(bundled)

        override fun parseHotRangesJson(body: String): List<String> = emptyList()

        override fun parseSpamDomainsJson(body: String): List<String> = emptyList()
    }

    private fun callFrom(
        number: String,
        verificationStatus: Int,
    ): SpamCheckResult =
        runBlocking {
            fixture.repository.isSpam(number, callerIdentity = CallerIdentity(verificationStatus = verificationStatus))
        }

    private companion object {
        const val NOT_VERIFIED = 0
        const val PASSED = 1
    }
}
