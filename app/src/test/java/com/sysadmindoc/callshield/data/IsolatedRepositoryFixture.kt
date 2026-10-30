package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.OkHttpExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.nio.file.Files
import java.time.Duration

/** Owns every persistent resource used by one Robolectric test sandbox. */
internal class IsolatedRepositoryFixture(
    context: Context,
    externalBlocklistDataSource: ExternalBlocklistDataSource = OkHttpExternalBlocklistDataSource(),
    remote: SpamDataSource = GitHubDataSource(),
    wallClock: () -> Long = System::currentTimeMillis,
    // Its own scorer: a test that boots CallShieldApp loads the bundled model
    // into SpamMLScorer.shared from appScope, which can land mid-way through a
    // later test in the same sandbox and turn its verdicts into ml_scorer blocks.
    checkerDependencies: CheckerDependencies = CheckerDependencies(spamMLScorer = SpamMLScorer()),
) : AutoCloseable {
    init {
        // The repository reads the region through a process-wide cache, so a
        // fixture starts from what this test's telephony shadows say.
        PhoneIdentityCanonicalizer.resetCacheForTests()
    }

    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
    private val storeDirectory = Files.createTempDirectory("callshield-test-datastore-").toFile()
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val dao = database.spamDao()

    /** The settings store under [repository], for tests that seed preferences directly. */
    val settingsStore = preferenceStore("settings")

    val repository =
        SpamRepository(
            context = context,
            database = database,
            settingsDataStore = settingsStore,
            privateSettingsDataStore = preferenceStore("private"),
            externalBlocklistDataSource = externalBlocklistDataSource,
            remote = remote,
            wallClock = wallClock,
            checkerDependencies = checkerDependencies,
        )

    private fun preferenceStore(name: String) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptPreferences(),
            scope = storeScope,
            produceFile = { File(storeDirectory, "$name.preferences_pb") },
        )

    /** Closes the database under the repository, so its next write throws. */
    fun breakDatabase() = database.close()

    /** The database as SQLite hands it to a corruption callback. */
    val sqlite: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    /** Drops [table], so every query on it throws while the rest of the database reads. */
    fun damageTable(table: String) = sqlite.execSQL("DROP TABLE `$table`")

    override fun close() {
        database.close()
        // DataStore runs each edit's transform on the caller's dispatcher, and a
        // ViewModel's caller is the main one. Under Robolectric the main thread is
        // this thread, so blocking it with runBlocking { cancelAndJoin() } hung a
        // test for hours when an edit was mid-flight at tear-down (NotSpamUndoTest,
        // 2026-10-29). Cancel, then keep the main looper moving until every child
        // of the store scope has stopped.
        storeJob.cancel()
        val deadline = System.nanoTime() + STORE_STOP_TIMEOUT.toNanos()
        while (!storeJob.isCompleted && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        check(storeJob.isCompleted) {
            "The fixture's store scope didn't stop within ${STORE_STOP_TIMEOUT.seconds} s"
        }
        check(storeDirectory.deleteRecursively()) {
            "Could not remove isolated repository fixture at ${storeDirectory.absolutePath}"
        }
    }

    private companion object {
        val STORE_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
