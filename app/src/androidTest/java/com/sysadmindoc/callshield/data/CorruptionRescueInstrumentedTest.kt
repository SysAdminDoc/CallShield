package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WildcardRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * The corruption path on real SQLite: one table's pages are overwritten on
 * disk, Room reports the damage as corruption, and the user's own rows come
 * through the rebuild.
 */
@RunWith(AndroidJUnit4::class)
class CorruptionRescueInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stores = TestSettingsStores(context)
    private val rescueFile = File(context.noBackupFilesDir, "corruption-rescue.json")

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        rescueFile.delete()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        rescueFile.delete()
        stores.close()
    }

    @Test
    fun aMalformedCallLogKeepsTheUsersRowsThroughTheRebuild() =
        runBlocking {
            val original = AppDatabase.builder(context, TEST_DB, afterRebuild = {}).build()
            val seeded = stores.repository(context, original)
            assertTrue(seeded.blockNumber("+12125550101", "robocall", "kept calling"))
            assertTrue(seeded.addToWhitelist("+14155550100", "Dr. Patel's office", rangeDigits = 2))
            original.spamDao().insertWildcardRule(WildcardRule(pattern = "+1832555*"))
            original.spamDao().insertKeywordRule(SmsKeywordRule(keyword = "gift card"))
            original.spamDao().insertBlockedCalls(
                List(LOG_ROWS) { BlockedCall(number = "+1212555${1000 + it}", isCall = false, smsBody = "x".repeat(LOG_BODY_CHARS)) },
            )
            val sqlite = original.openHelper.writableDatabase
            val pageSize =
                sqlite.query("PRAGMA page_size").use {
                    it.moveToFirst()
                    it.getLong(0)
                }
            val rootPage =
                sqlite.query("SELECT rootpage FROM sqlite_master WHERE name = 'call_log'").use {
                    it.moveToFirst()
                    it.getLong(0)
                }
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            original.close()
            RandomAccessFile(context.getDatabasePath(TEST_DB), "rw").use { file ->
                file.seek((rootPage - 1) * pageSize)
                file.write(ByteArray(pageSize.toInt()) { 0x5A })
            }

            var rebuilds = 0
            val corrupt = AppDatabase.builder(context, TEST_DB, afterRebuild = { rebuilds++ }).build()
            try {
                val failure =
                    runCatching {
                        corrupt.openHelper.readableDatabase.query("SELECT smsBody FROM call_log").use { cursor ->
                            while (cursor.moveToNext()) cursor.getString(0)
                        }
                    }.exceptionOrNull()
                assertTrue("reading the damaged table should fail as corruption: $failure", AppDatabase.isCorruptionException(failure))
                assertEquals("SQLite replaced the file once", 1, rebuilds)
                assertTrue(CorruptionRescue.isPending(context))

                // The same Room instance carries on with the empty file SQLite made.
                assertTrue(CorruptionRescue.importPending(context, corrupt.spamDao(), stores.repository(context, corrupt)))
                val dao = corrupt.spamDao()
                assertEquals(
                    0,
                    corrupt.openHelper.readableDatabase.query("SELECT COUNT(*) FROM call_log").use {
                        it.moveToFirst()
                        it.getInt(0)
                    },
                )
                assertEquals(listOf("+12125550101"), dao.getUserBlockedNumbers().first().map { it.number })
                assertEquals(listOf("+14155550100" to 2), dao.getAllWhitelist().first().map { it.number to it.rangeDigits })
                assertEquals(listOf("+1832555*"), dao.getAllWildcardRules().first().map { it.pattern })
                assertEquals(listOf("gift card"), dao.getAllKeywordRules().first().map { it.keyword })
            } finally {
                corrupt.close()
            }
        }

    private companion object {
        const val TEST_DB = "corruption-rescue-test.db"
        const val LOG_ROWS = 200
        const val LOG_BODY_CHARS = 400
    }
}
