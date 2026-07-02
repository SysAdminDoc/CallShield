package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@Suppress("MaxLineLength")
class ExternalBlocklistSubscriptionIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var feed: FakeExternalBlocklistDataSource
    private lateinit var syncRepository: SyncRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var privateDataStoreFile: File
    private var invalidations = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreFile = File(context.cacheDir, "external-blocklists-${System.nanoTime()}.preferences_pb")
        privateDataStoreFile = File(context.cacheDir, "external-blocklists-private-${System.nanoTime()}.preferences_pb")
        settings =
            SettingsRepository(
                dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { dataStoreFile },
                    ),
                privateDataStore =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { privateDataStoreFile },
                    ),
            )
        feed = FakeExternalBlocklistDataSource()
        syncRepository =
            SyncRepository(
                context = context,
                dao = db.spamDao(),
                remote = FakeSpamDataSource,
                settingsRepository = settings,
                normalizeNumber = ::digitsOnly,
                invalidateAllCaches = { invalidations++ },
                externalBlocklistDataSource = feed,
            )
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        privateDataStoreFile.delete()
    }

    @Test
    fun subscriptionPreviewApplyRefreshAndDisableUseFeedOwnedRows() =
        runBlocking {
            val dao = db.spamDao()
            val url = "https://lists.example.test/feed.csv"
            val source = externalBlocklistSubscriptionSource(url)
            feed.body =
                """
                phone,type,description
                212-555-0101,robocall,Feed row
                508-555-0102,scam,Duplicate github row
                """.trimIndent()
            dao.insertNumber(
                SpamNumber(
                    number = "5085550102",
                    type = "database",
                    reports = 99,
                    description = "GitHub row",
                    source = "github",
                ),
            )

            val preview = syncRepository.previewExternalBlocklistSubscription(url, "Carrier leaks")

            assertTrue(preview.success)
            assertEquals(1, preview.preview?.numberCount)
            assertEquals(1, preview.preview?.blockedByOtherSources)
            assertEquals(1, preview.preview?.added)
            assertEquals(0, dao.getCountBySource(source))

            val applied = syncRepository.applyExternalBlocklistSubscription(url, "Carrier leaks")

            assertTrue(applied.success)
            assertEquals(source, applied.subscription?.source)
            assertEquals(1, dao.getCountBySource(source))
            assertEquals(source, dao.findByNumber("2125550101")?.source)
            assertEquals("github", dao.findByNumber("5085550102")?.source)
            assertEquals(1, settings.readExternalBlocklistSubscriptions().size)
            assertTrue(invalidations > 0)

            feed.body =
                """
                phone,type,description
                650-555-0103,scam,Replacement row
                """.trimIndent()
            val refreshed = syncRepository.applyExternalBlocklistSubscription(url, "Carrier leaks")

            assertTrue(refreshed.success)
            assertEquals(1, refreshed.preview?.added)
            assertEquals(1, refreshed.preview?.removed)
            assertNull(dao.findByNumber("2125550101"))
            assertEquals(source, dao.findByNumber("6505550103")?.source)

            val disabled =
                syncRepository.setExternalBlocklistSubscriptionEnabled(
                    id = applied.subscription?.id.orEmpty(),
                    enabled = false,
                )

            assertTrue(disabled.success)
            assertEquals(0, dao.getCountBySource(source))
            assertNull(dao.findByNumber("6505550103"))
            assertNotNull(dao.findByNumber("5085550102"))
            assertFalse(settings.readExternalBlocklistSubscriptions().single().enabled)
        }

    @Test
    fun largeSubscriptionFeedUsesChunkedExistingNumberLookups() =
        runBlocking {
            val dao = db.spamDao()
            val url = "https://lists.example.test/large.txt"
            val source = externalBlocklistSubscriptionSource(url)
            feed.body =
                (1..1_205).joinToString(separator = "\n") { index ->
                    "212555%04d".format(index)
                }

            val applied = syncRepository.applyExternalBlocklistSubscription(url, "Large feed")

            assertTrue(applied.success)
            assertEquals(1_205, applied.preview?.numberCount)
            assertEquals(1_205, dao.getCountBySource(source))
        }

    private fun externalBlocklistSubscriptionSource(url: String): String = "subscription:${ExternalBlocklistParser.idForUrl(url)}"

    private fun digitsOnly(raw: String): String = raw.filter { it in '0'..'9' }

    private class FakeExternalBlocklistDataSource : ExternalBlocklistDataSource {
        var body: String = ""

        override suspend fun fetchText(url: String): Result<String> = Result.success(body)
    }

    private object FakeSpamDataSource : SpamDataSource {
        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ): Result<SpamDatabase> = Result.failure(IllegalStateException("not used"))

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> = Result.failure(IllegalStateException("not used"))

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.failure(IllegalStateException("not used"))
    }
}
