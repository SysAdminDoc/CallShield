package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupKeyword
import com.sysadmindoc.callshield.data.BackupRestore.BackupLogEntry
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupRangeRule
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.BackupWildcard
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
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

    @Test
    fun selectedReplaceRestoreOnlyClearsSelectedSections() =
        runBlocking {
            dao.insertNumber(
                SpamNumber(number = "+15550000001", type = "spam", source = "user", isUserBlocked = true),
            )
            dao.insertWhitelistEntry(WhitelistEntry(number = "+15550000002", description = "Existing"))
            dao.insertWildcardRule(WildcardRule(pattern = "old?", isRegex = false, description = "Existing"))
            dao.insertKeywordRule(SmsKeywordRule(keyword = "legacy", caseSensitive = false, description = "Existing"))
            dao.insertHashWildcardRule(HashWildcardRule(pattern = "+1555#######", description = "Old range"))
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15550000003",
                    timestamp = 100L,
                    matchReason = "Old log",
                    logKey = "old-log",
                ),
            )

            val backup =
                Backup(
                    blockedNumbers = listOf(BackupNumber("+15559990000", "spam", "Ignored", "user")),
                    whitelistNumbers = listOf(BackupWhitelist("+15559990001", "Ignored")),
                    wildcardRules = listOf(BackupWildcard("900*", isRegex = false, description = "Ignored", enabled = true)),
                    keywordRules = listOf(BackupKeyword("verify", caseSensitive = false, description = "Ignored", enabled = true)),
                    rangeRules = listOf(BackupRangeRule("+1666#######", "New range", enabled = true)),
                    logs =
                        listOf(
                            BackupLogEntry(
                                number = "+16660000000",
                                timestamp = 200L,
                                matchReason = "New log",
                                logKey = "new-log",
                            ),
                        ),
                )

            val preview = preview(backup, setOf(BackupSection.RANGE_RULES, BackupSection.LOGS))
            val result =
                BackupRestore.restorePayload(
                    context = context,
                    payload = preview.payload,
                    mode = BackupRestore.RestoreMode.REPLACE,
                    dao = dao,
                    repo = repo,
                    selectedSections = preview.selectedSections,
                )

            assertTrue(result.success)
            assertNotNull(dao.findByNumber("+15550000001"))
            assertEquals(listOf("+15550000002"), dao.getAllWhitelist().first().map { it.number })
            assertEquals(listOf("old?"), dao.getAllWildcardRules().first().map { it.pattern })
            assertEquals(listOf("legacy"), dao.getAllKeywordRules().first().map { it.keyword })
            assertEquals(listOf("+1666#######"), dao.getAllHashWildcardRules().first().map { it.pattern })
            assertEquals(listOf("new-log"), dao.getBlockedCalls().first().map { it.logKey })
        }

    @Test
    fun settingsRestoreAppliesSelectedSettingsWithoutOtherSections() =
        runBlocking {
            repo.setBlockCalls(true)
            repo.setFreqEscalation(true)
            repo.setFreqThreshold(3)
            repo.setActiveProfileName(null)

            val preview =
                preview(
                    Backup(
                        blockedNumbers = listOf(BackupNumber("+15559990000", "spam", "Ignored", "user")),
                        settings =
                            BackupSettings(
                                blockCallsEnabled = false,
                                frequencyEscalationEnabled = false,
                                frequencyThreshold = 8,
                                activeProfileName = BlockingProfiles.Profile.SLEEP.name,
                            ),
                    ),
                    setOf(BackupSection.SETTINGS),
                )
            val result =
                BackupRestore.restorePayload(
                    context = context,
                    payload = preview.payload,
                    mode = BackupRestore.RestoreMode.MERGE,
                    dao = dao,
                    repo = repo,
                    selectedSections = preview.selectedSections,
                )

            assertTrue(result.success)
            assertNull(dao.findByNumber("+15559990000"))
            assertFalse(repo.blockCallsEnabled.first())
            assertFalse(repo.freqEscalationEnabled.first())
            assertEquals(8, repo.freqThreshold.first())
            assertEquals(BlockingProfiles.Profile.SLEEP.name, repo.activeProfileName.first())

            repo.setBlockCalls(true)
            repo.setFreqEscalation(true)
            repo.setFreqThreshold(3)
            repo.setActiveProfileName(null)
            Unit
        }

    private suspend fun preview(
        backup: Backup,
        sections: Set<BackupSection> = BackupRestore.defaultRestoreSections,
    ): BackupRestore.RestorePreview {
        val result = BackupRestore.previewRestoreJson(context, BackupRestore.backupToJson(backup), dao, sections)
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
