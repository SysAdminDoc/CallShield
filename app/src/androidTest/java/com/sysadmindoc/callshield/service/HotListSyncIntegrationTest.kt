package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HotListSyncIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: SpamRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = SpamRepository(context, db)
        SpamHeuristics.updateHotRanges(emptyList())
        SmsContentAnalyzer.updateSpamDomains(emptyList())
    }

    @After
    fun tearDown() {
        SpamHeuristics.updateHotRanges(emptyList())
        SmsContentAnalyzer.updateSpamDomains(emptyList())
        db.close()
    }

    @Test
    fun refreshLoadsHotNumbersRangesAndSpamDomainsFromFakeFeed() =
        runBlocking {
            val dao = db.spamDao()
            dao.insertNumber(
                SpamNumber(
                    number = "+12125550101",
                    type = "database",
                    reports = 99,
                    description = "Main database row",
                    source = "github",
                ),
            )
            val source =
                FakeHotFeedDataSource(
                    hotList =
                        listOf(
                            HotNumber("+1 (212) 555-0101", "robocall", "Duplicate of stronger row"),
                            HotNumber("508-555-0102", "scam", "Fresh hot row"),
                            HotNumber("not-a-number", "scam", "Invalid row"),
                            HotNumber("+1 508 555 0102", "scam", "Duplicate fresh row"),
                        ),
                    hotRanges = listOf("212555", "bad", "12345", "508555", "212555"),
                    spamDomains = listOf("https://www.Bad.Example/path", "", "clean.example:443", "bad.example"),
                )

            val outcome = HotDataSync.refresh(context, source, repo, dao)

            assertTrue(outcome.refreshedAnyFeed)
            assertTrue(outcome.hasAnyHotProtection)

            assertEquals(1, dao.getCountBySource("hot_list"))
            val freshHot = dao.findByNumber(repo.normalizeNumber("508-555-0102"))
            assertEquals("hot_list", freshHot?.source)
            assertEquals("Fresh hot row", freshHot?.description)

            val strongerExisting = dao.findByNumber("+12125550101")
            assertEquals("github", strongerExisting?.source)
            assertEquals(99, strongerExisting?.reports)

            assertTrue(SpamHeuristics.isHotCampaignRange("+12125550123"))
            assertTrue(SpamHeuristics.isHotCampaignRange("+15085550123"))
            assertFalse(SpamHeuristics.isHotCampaignRange("+19995550123"))

            val smsResult = SmsContentAnalyzer.analyze("Claim now at https://bad.example/login")
            assertTrue(smsResult.reasons.contains("spam_domain"))
        }

    private class FakeHotFeedDataSource(
        private val hotList: List<HotNumber>,
        private val hotRanges: List<String>,
        private val spamDomains: List<String>,
    ) : HotFeedDataSource {
        override suspend fun fetchHotList(
            owner: String,
            repo: String,
        ): Result<List<HotNumber>> = Result.success(hotList)

        override suspend fun fetchHotRanges(
            owner: String,
            repo: String,
        ): Result<List<String>> = Result.success(hotRanges)

        override suspend fun fetchSpamDomains(
            owner: String,
            repo: String,
        ): Result<List<String>> = Result.success(spamDomains)

        override fun parseHotListJson(body: String): List<HotNumber> = hotList

        override fun parseHotRangesJson(body: String): List<String> = hotRanges

        override fun parseSpamDomainsJson(body: String): List<String> = spamDomains
    }
}
