package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupKeyword
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.BackupWildcard
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import kotlinx.coroutines.flow.first
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

@RunWith(AndroidJUnit4::class)
class BackupRestoreIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: SpamDao
    private lateinit var repo: SpamRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val builder = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        db = builder.allowMainThreadQueries().build()
        dao = db.spamDao()
        repo = SpamRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun previewReportsCountsAndConflictsBeforeMutation() =
        runBlocking {
            dao.insertNumber(
                SpamNumber(number = "+15550000001", type = "spam", source = "user", isUserBlocked = true),
            )
            dao.insertWhitelistEntry(WhitelistEntry(number = "+15550000002", description = "Existing"))
            dao.insertWildcardRule(WildcardRule(pattern = "800*", isRegex = false, description = "Existing"))
            dao.insertKeywordRule(SmsKeywordRule(keyword = "verify", caseSensitive = true, description = "Existing"))

            val backup =
                Backup(
                    blockedNumbers =
                        listOf(
                            BackupNumber("+15550000001", "spam", "Existing", "user"),
                            BackupNumber("+15550000003", "spam", "New", "user"),
                        ),
                    whitelistNumbers = listOf(BackupWhitelist("+15550000002", "Existing")),
                    wildcardRules =
                        listOf(
                            BackupWildcard("800*", isRegex = false, description = "Existing", enabled = true),
                        ),
                    keywordRules =
                        listOf(
                            BackupKeyword("verify", caseSensitive = true, description = "Existing", enabled = true),
                        ),
                )
            val preview = preview(backup)

            assertEquals(BackupRestore.RestoreCounts(2, 1, 1, 1), preview.counts)
            assertEquals(BackupRestore.RestoreCounts(1, 1, 1, 1), preview.conflicts)
            assertNull(dao.findByNumber("+15550000003"))
        }

    @Test
    fun mergeRestoreAddsBackupItemsWithoutClearingExistingLocalState() =
        runBlocking {
            dao.insertNumber(
                SpamNumber(number = "+15550000001", type = "spam", source = "user", isUserBlocked = true),
            )
            dao.insertWhitelistEntry(WhitelistEntry(number = "+15550000002", description = "Existing"))
            dao.insertWildcardRule(WildcardRule(pattern = "old?", isRegex = false, description = "Existing"))
            dao.insertKeywordRule(SmsKeywordRule(keyword = "legacy", caseSensitive = false, description = "Existing"))

            val preview = preview(completeBackup())
            val result =
                BackupRestore.restorePayload(context, preview.payload, BackupRestore.RestoreMode.MERGE, dao, repo)
            val whitelistEntries = dao.getAllWhitelist().first()
            val wildcardRules = dao.getAllWildcardRules().first()
            val keywordRules = dao.getAllKeywordRules().first()
            val whitelistNumbers = whitelistEntries.map { it.number }.toSet()
            val wildcardPatterns = wildcardRules.map { it.pattern }.toSet()
            val keywords = keywordRules.map { it.keyword }.toSet()

            assertTrue(result.success)
            assertNotNull(dao.findByNumber("+15550000001"))
            assertNotNull(dao.findByNumber("+15559990000"))
            assertEquals(setOf("+15550000002", "+15559990001"), whitelistNumbers)
            assertEquals(setOf("old?", "900*"), wildcardPatterns)
            assertEquals(setOf("legacy", "verify"), keywords)
        }

    @Test
    fun replaceRestoreClearsLocalBackupSectionsButPreservesSyncedFeeds() =
        runBlocking {
            dao.insertNumber(
                SpamNumber(number = "+15550000001", type = "spam", source = "user", isUserBlocked = true),
            )
            dao.insertNumber(
                SpamNumber(number = "+15550000002", type = "spam", source = "github", isUserBlocked = true),
            )
            dao.insertWhitelistEntry(WhitelistEntry(number = "+15550000003", description = "Existing"))
            dao.insertWildcardRule(WildcardRule(pattern = "old?", isRegex = false, description = "Existing"))
            dao.insertKeywordRule(SmsKeywordRule(keyword = "legacy", caseSensitive = false, description = "Existing"))

            val preview = preview(completeBackup())
            val result =
                BackupRestore.restorePayload(context, preview.payload, BackupRestore.RestoreMode.REPLACE, dao, repo)
            val whitelistEntries = dao.getAllWhitelist().first()
            val wildcardRules = dao.getAllWildcardRules().first()
            val keywordRules = dao.getAllKeywordRules().first()
            val restoredWhitelist = whitelistEntries.map { it.number }
            val restoredWildcards = wildcardRules.map { it.pattern }
            val restoredKeywords = keywordRules.map { it.keyword }

            assertTrue(result.success)
            assertNull(dao.findByNumber("+15550000001"))
            val syncedNumber = dao.findByNumber("+15550000002")
            assertNotNull(syncedNumber)
            assertFalse(syncedNumber!!.isUserBlocked)
            assertEquals("github", syncedNumber.source)
            assertNotNull(dao.findByNumber("+15559990000"))
            assertEquals(listOf("+15559990001"), restoredWhitelist)
            assertEquals(listOf("900*"), restoredWildcards)
            assertEquals(listOf("verify"), restoredKeywords)
        }

    private suspend fun preview(backup: Backup): BackupRestore.RestorePreview {
        val result = BackupRestore.previewRestoreJson(context, BackupRestore.backupToJson(backup), dao)
        assertTrue(result.message, result.success)
        return result.preview!!
    }

    private fun completeBackup(): Backup =
        Backup(
            blockedNumbers = listOf(BackupNumber("+15559990000", "spam", "Restored", "user")),
            whitelistNumbers = listOf(BackupWhitelist("+15559990001", "Restored", isEmergency = true)),
            wildcardRules = listOf(BackupWildcard("900*", isRegex = false, description = "Restored", enabled = true)),
            keywordRules =
                listOf(
                    BackupKeyword("verify", caseSensitive = false, description = "Restored", enabled = true),
                ),
        )
}
