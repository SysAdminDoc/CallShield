package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabaseShard
import com.sysadmindoc.callshield.data.model.SpamShardDescriptor
import com.sysadmindoc.callshield.data.model.SpamShardManifest
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.data.remote.sha256Hex
import com.sysadmindoc.callshield.data.remote.spamShardIdFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The shard hashes and commit id live in settings, which a cloud backup
 * restores, while the database doesn't come back, and a database rebuilt
 * after corruption starts empty too. Either way the stored hashes describe
 * rows that aren't there, so the next sync has to fetch them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptiedDatabaseResyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val remote = ShardedRemote()
    private val fixture = IsolatedRepositoryFixture(context, remote = remote)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `an emptied database fetches every shard at the next sync, even at the same commit`() {
        sync()
        assertEquals(SHARD_PATHS, remote.fetched.toSet())
        remote.fetched.clear()
        runBlocking { fixture.dao.replaceGithubData(emptyList(), emptyList()) }

        assertTrue(sync().success)

        assertEquals(SHARD_PATHS, remote.fetched.toSet())
        assertEquals(NUMBERS.toSet(), storedNumbers())
    }

    @Test
    fun `a database that still holds its rows fetches nothing again`() {
        sync()
        remote.fetched.clear()

        sync()
        remote.headCommit = "c2"
        sync()

        assertTrue(remote.fetched.isEmpty())
        assertEquals(NUMBERS.toSet(), storedNumbers())
    }

    @Test
    fun `a shard that lost its rows is fetched again on its own`() {
        sync()
        remote.fetched.clear()
        val (lostShard, lostNumbers) = SHARDS.entries.first()
        val kept = runBlocking { fixture.dao.getNumbersBySource("github") }.filter { it.number !in lostNumbers }
        runBlocking { fixture.dao.replaceGithubData(kept, emptyList()) }
        remote.headCommit = "c2"

        sync()

        assertEquals(listOf(pathOf(lostShard)), remote.fetched)
        assertEquals(NUMBERS.toSet(), storedNumbers())
    }

    private fun sync() = runBlocking { fixture.repository.syncFromGitHub(force = false) }

    private fun storedNumbers() = runBlocking { fixture.dao.getNumbersBySource("github") }.mapTo(HashSet()) { it.number }

    private class ShardedRemote : SpamDataSource {
        var headCommit = "c1"
        val fetched = mutableListOf<String>()
        private val parser = GitHubDataSource()

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> = Result.success(headCommit)

        override suspend fun fetchSpamShardManifest(
            owner: String,
            repo: String,
        ): Result<SpamShardManifest> = Result.success(MANIFEST)

        override suspend fun fetchSpamShardJson(
            path: String,
            owner: String,
            repo: String,
        ): Result<String> =
            BODIES[path]?.let {
                fetched += path
                Result.success(it)
            } ?: Result.failure(IOException("no shard at $path"))

        override fun parseSpamShardJson(body: String): Result<SpamDatabaseShard> = parser.parseSpamShardJson(body)

        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ): Result<SpamDatabase> = Result.failure(IllegalStateException("the shards should have served this sync"))

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.failure(UnsupportedOperationException())
    }

    private companion object {
        val NUMBERS = listOf("+12125550101", "+13105550102", "+14155550103", "+17185550104", "+19175550105")
        val SHARDS: Map<String, List<String>> = NUMBERS.groupBy(::spamShardIdFor)

        fun pathOf(shard: String) = "data/spam_number_shards/$shard.json"

        val BODIES: Map<String, String> =
            SHARDS.entries.associate { (shard, numbers) ->
                val rows = numbers.joinToString(",") { """{"number":"$it","type":"robocall","reports":5}""" }
                pathOf(shard) to """{"shard_id":"$shard","numbers":[$rows],"prefixes":[]}"""
            }
        val SHARD_PATHS = BODIES.keys

        val MANIFEST =
            SpamShardManifest(
                formatVersion = 1,
                version = 2,
                updated = "2026-09-21",
                legacyPath = GitHubDataSource.DATA_PATH,
                shardDirectory = "data/spam_number_shards",
                shardCount = 256,
                shards =
                    SHARDS.map { (shard, numbers) ->
                        val bytes = BODIES.getValue(pathOf(shard)).toByteArray(Charsets.UTF_8)
                        SpamShardDescriptor(shard, pathOf(shard), sha256Hex(bytes), bytes.size.toLong(), numbers.size, 0)
                    },
            )

        init {
            check(SHARDS.size >= 2) { "the test needs numbers in at least two shards" }
        }
    }
}
