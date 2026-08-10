package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamNumberJson
import com.sysadmindoc.callshield.data.model.SpamPrefixJson
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncFromGitHubPopulatesInMemoryRoomDatabaseFromRemoteSnapshot() =
        runBlocking {
            val remote =
                FakeSpamDataSource(
                    sha = "abc123",
                    database =
                        SpamDatabase(
                            version = 42,
                            updated = "2026-05-17",
                            numbers =
                                listOf(
                                    SpamNumberJson(
                                        number = "(212) 555-0101",
                                        type = "robocall",
                                        reports = 4,
                                        description = "Remote robocall",
                                    ),
                                    SpamNumberJson(
                                        number = "+1 508 555 0102",
                                        type = "scam",
                                        reports = 2,
                                        description = "Remote scam",
                                    ),
                                ),
                            prefixes =
                                listOf(
                                    SpamPrefixJson(
                                        prefix = "+1508555",
                                        type = "campaign",
                                        description = "Remote campaign prefix",
                                    ),
                                ),
                        ),
                )
            val repo = SpamRepository(context, db, remote)

            val result = repo.syncFromGitHub(force = true)

            assertTrue(result.success)
            assertEquals("Sync complete — numbers: 2, prefixes: 1", result.message)
            assertEquals(1, remote.fetchCount)
            assertEquals(1, remote.updateCheckCount)

            val dao = db.spamDao()
            val syncedNumber = dao.findByNumber(repo.normalizeNumber("(212) 555-0101"))
            assertEquals("robocall", syncedNumber?.type)
            assertEquals("github", syncedNumber?.source)
            assertEquals(4, syncedNumber?.reports)

            val plusNumber = dao.findByNumber("+15085550102")
            assertEquals("scam", plusNumber?.type)
            assertEquals("Remote scam", plusNumber?.description)

            val prefixes = dao.getAllPrefixes(System.currentTimeMillis())
            assertEquals(1, prefixes.size)
            assertEquals("+1508555", prefixes.single().prefix)
        }

    @Test
    fun syncFromGitHubPreservesUserBlockedFlagOnRemoteRefresh() =
        runBlocking {
            val dao = db.spamDao()
            dao.insertNumber(
                SpamNumber(
                    number = "+15085550103",
                    type = "manual",
                    description = "User blocked",
                    source = "user",
                    isUserBlocked = true,
                ),
            )
            val remote =
                FakeSpamDataSource(
                    sha = "def456",
                    database =
                        SpamDatabase(
                            version = 43,
                            updated = "2026-05-17",
                            numbers =
                                listOf(
                                    SpamNumberJson(
                                        number = "+1 508 555 0103",
                                        type = "robocall",
                                        reports = 9,
                                        description = "Now in remote database",
                                    ),
                                ),
                            prefixes = emptyList(),
                        ),
                )
            val repo = SpamRepository(context, db, remote)

            val result = repo.syncFromGitHub(force = true)

            assertTrue(result.success)
            val refreshed = dao.findByNumber("+15085550103")
            assertEquals("github", refreshed?.source)
            assertTrue(refreshed?.isUserBlocked == true)
            assertEquals(9, refreshed?.reports)
            assertFalse(refreshed?.description.isNullOrBlank())
        }

    private class FakeSpamDataSource(
        private val sha: String,
        private val database: SpamDatabase,
    ) : SpamDataSource {
        var fetchCount = 0
            private set
        var updateCheckCount = 0
            private set

        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ): Result<SpamDatabase> {
            fetchCount++
            return Result.success(database)
        }

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> {
            updateCheckCount++
            return Result.success(sha)
        }

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.success(database)
    }
}
