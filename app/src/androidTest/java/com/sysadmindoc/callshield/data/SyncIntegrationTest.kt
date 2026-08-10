package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabaseShard
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamNumberJson
import com.sysadmindoc.callshield.data.model.SpamPrefixJson
import com.sysadmindoc.callshield.data.model.SpamShardDescriptor
import com.sysadmindoc.callshield.data.model.SpamShardManifest
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.data.remote.sha256Hex
import com.sysadmindoc.callshield.data.remote.spamShardIdFor
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

    @Test
    fun shardedSyncFetchesOnlyChangedPayloadsAndRejectsPartialUpdates() =
        runBlocking {
            val firstNumber = "+12125550101"
            val secondNumber = "+12125550102"
            val firstId = spamShardIdFor(firstNumber)
            val secondId = spamShardIdFor(secondNumber)
            assertFalse(firstId == secondId)

            val firstShard = shardFixture(firstId, firstNumber, "Initial first shard")
            val secondShard = shardFixture(secondId, secondNumber, "Initial second shard")
            val remote =
                ShardedFakeSpamDataSource(
                    sha = "sharded-v1",
                    initialShards = listOf(firstShard, secondShard),
                )
            val repo = SpamRepository(context, db, remote)

            val initial = repo.syncFromGitHub(force = true)

            assertTrue(initial.success)
            assertEquals(2, remote.fetchShardCount)
            assertEquals(2, db.spamDao().getCountBySource("github"))

            val unchanged = repo.syncFromGitHub()
            assertTrue(unchanged.success)
            assertEquals(2, remote.fetchShardCount)

            val changedFirst = shardFixture(firstId, firstNumber, "Updated first shard")
            remote.sha = "sharded-v2"
            remote.replaceShards(listOf(changedFirst, secondShard))
            val incremental = repo.syncFromGitHub()

            assertTrue(incremental.success)
            assertEquals(3, remote.fetchShardCount)
            assertEquals("Updated first shard", db.spamDao().findByNumber(firstNumber)?.description)
            assertEquals("Initial second shard", db.spamDao().findByNumber(secondNumber)?.description)

            val beforeFailedUpdate = db.spamDao().findByNumber(firstNumber)
            remote.sha = "sharded-v3"
            remote.replaceShards(listOf(changedFirst.copy(hashOverride = "0".repeat(64)), secondShard))
            val failed = repo.syncFromGitHub()

            assertTrue(failed.success)
            assertTrue(failed.warning)
            assertEquals(beforeFailedUpdate, db.spamDao().findByNumber(firstNumber))
        }

    private fun shardFixture(
        id: String,
        number: String,
        description: String,
    ): ShardFixture {
        val payload =
            SpamDatabaseShard(
                shardId = id,
                numbers =
                    listOf(
                        SpamNumberJson(
                            number = number,
                            type = "scam",
                            reports = 3,
                            description = description,
                        ),
                    ),
                prefixes = emptyList(),
            )
        val body = "payload:$id:$number:$description"
        return ShardFixture(
            descriptor =
                SpamShardDescriptor(
                    id = id,
                    path = "data/spam_number_shards/$id.json",
                    sha256 = sha256Hex(body.toByteArray(Charsets.UTF_8)),
                    bytes = body.toByteArray(Charsets.UTF_8).size.toLong(),
                    numbers = payload.numbers.size,
                    prefixes = payload.prefixes.size,
                ),
            body = body,
            payload = payload,
        )
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

    private data class ShardFixture(
        val descriptor: SpamShardDescriptor,
        val body: String,
        val payload: SpamDatabaseShard,
        val hashOverride: String? = null,
    ) {
        val effectiveDescriptor: SpamShardDescriptor
            get() = descriptor.copy(sha256 = hashOverride ?: descriptor.sha256)
    }

    private class ShardedFakeSpamDataSource(
        var sha: String,
        initialShards: List<ShardFixture>,
    ) : SpamDataSource {
        private val shards = linkedMapOf<String, ShardFixture>()
        var fetchShardCount = 0
            private set

        init {
            replaceShards(initialShards)
        }

        fun replaceShards(next: List<ShardFixture>) {
            shards.clear()
            next.forEach { fixture -> shards[fixture.descriptor.id] = fixture }
        }

        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ): Result<SpamDatabase> = Result.failure(IllegalStateException("legacy fallback not expected"))

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> = Result.success(sha)

        override suspend fun fetchSpamShardManifest(
            owner: String,
            repo: String,
        ): Result<SpamShardManifest> =
            Result.success(
                SpamShardManifest(
                    formatVersion = 1,
                    version = 1,
                    updated = "2026-08-10",
                    legacyPath = "data/spam_numbers.json",
                    shardDirectory = "data/spam_number_shards",
                    shardCount = 256,
                    shards = shards.values.map { it.effectiveDescriptor },
                ),
            )

        override suspend fun fetchSpamShardJson(
            path: String,
            owner: String,
            repo: String,
        ): Result<String> {
            fetchShardCount++
            return shards.values
                .firstOrNull { it.descriptor.path == path }
                ?.let { Result.success(it.body) }
                ?: Result.failure(IllegalArgumentException("missing shard $path"))
        }

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.failure(IllegalStateException("legacy fallback not expected"))

        override fun parseSpamShardJson(body: String): Result<SpamDatabaseShard> =
            shards.values
                .firstOrNull { it.body == body }
                ?.let { Result.success(it.payload) }
                ?: Result.failure(IllegalArgumentException("unknown shard payload"))
    }
}
