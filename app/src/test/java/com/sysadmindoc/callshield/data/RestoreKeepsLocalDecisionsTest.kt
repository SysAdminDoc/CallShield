package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.RestoreMode
import com.sysadmindoc.callshield.data.BackupRestore.RestorePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A restore let the backup's decision for a number replace the one on this
 * phone. A Merge deleted an allow, an emergency one included, for a number
 * the backup blocked, and a plain allow cleared an emergency flag. Neither
 * had an Undo, and the preview didn't count them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RestoreKeepsLocalDecisionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private val repo get() = fixture.repository
    private val dao get() = fixture.dao

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a merge keeps the allow or block a number has here and still adds what's new`() =
        runBlocking {
            repo.addToWhitelist(DOCTOR, "Dr. Lee", isEmergency = true)
            repo.addToWhitelist(PHARMACY, "Pharmacy", isEmergency = true)
            repo.blockNumber(BLOCKED_HERE, "spam")
            val payload =
                RestorePayload(
                    blockedNumbers =
                        listOf(
                            BackupNumber(DOCTOR, "spam", "old block"),
                            BackupNumber(BLOCKED_HERE, "robocall", "old block"),
                            BackupNumber(NEW_SPAM, "spam", "new"),
                        ),
                    whitelistNumbers = listOf(BackupWhitelist(PHARMACY, "Pharmacy"), BackupWhitelist(BLOCKED_HERE, "old allow")),
                )

            val result = BackupRestore.restorePayload(context, payload, RestoreMode.MERGE, dao, repo, NUMBER_SECTIONS)

            assertTrue(result.message, result.success)
            assertEquals(true, dao.findWhitelistEntry(DOCTOR)?.isEmergency)
            assertFalse(dao.findByNumber(DOCTOR)?.isUserBlocked == true)
            assertEquals(true, dao.findWhitelistEntry(PHARMACY)?.isEmergency)
            assertNull(dao.findWhitelistEntry(BLOCKED_HERE))
            assertEquals(true, dao.findByNumber(BLOCKED_HERE)?.isUserBlocked)
            // The block here stays as the user saved it, not relabeled from the backup.
            assertEquals("spam", dao.findByNumber(BLOCKED_HERE)?.type)
            assertEquals(true, dao.findByNumber(NEW_SPAM)?.isUserBlocked)
        }

    @Test
    fun `a backup's permanent decision still replaces a temporary one here`() =
        runBlocking {
            val later = System.currentTimeMillis() + 3_600_000L
            repo.temporaryBlockNumber(DOCTOR, later, "spam")
            repo.temporaryAllowNumber(NEW_SPAM, later)
            val payload =
                RestorePayload(
                    blockedNumbers = listOf(BackupNumber(NEW_SPAM, "spam", "old block")),
                    whitelistNumbers = listOf(BackupWhitelist(DOCTOR, "Dr. Lee", isEmergency = true)),
                )

            val result = BackupRestore.restorePayload(context, payload, RestoreMode.MERGE, dao, repo, NUMBER_SECTIONS)

            assertTrue(result.message, result.success)
            assertEquals(true, dao.findWhitelistEntry(DOCTOR)?.isEmergency)
            assertFalse(dao.findByNumber(DOCTOR)?.isUserBlocked == true)
            assertNull(dao.findWhitelistEntry(NEW_SPAM))
            assertEquals(null, dao.findByNumber(NEW_SPAM)?.expiresAt)
            assertEquals(true, dao.findByNumber(NEW_SPAM)?.isUserBlocked)
        }

    @Test
    fun `replacing only the blocked numbers leaves an allow the backup blocks`() =
        runBlocking {
            repo.addToWhitelist(DOCTOR, "Dr. Lee", isEmergency = true)
            val payload = RestorePayload(blockedNumbers = listOf(BackupNumber(DOCTOR, "spam", "old block")))

            val result =
                BackupRestore.restorePayload(context, payload, RestoreMode.REPLACE, dao, repo, setOf(BackupSection.BLOCKED_NUMBERS))

            assertTrue(result.message, result.success)
            assertEquals(true, dao.findWhitelistEntry(DOCTOR)?.isEmergency)
        }

    @Test
    fun `the preview counts a backup block that meets an allow here`() =
        runBlocking {
            repo.addToWhitelist(DOCTOR, "Dr. Lee", isEmergency = true)
            val json = BackupRestore.backupToJson(Backup(blockedNumbers = listOf(BackupNumber(DOCTOR, "spam", "old block"))))

            val preview = BackupRestore.previewRestoreJson(context, json, dao).preview

            assertEquals(1, preview?.conflicts?.blockedNumbers)
        }

    private companion object {
        const val DOCTOR = "+12125550141"
        const val PHARMACY = "+12125550142"
        const val BLOCKED_HERE = "+12125550143"
        const val NEW_SPAM = "+12125550144"
        val NUMBER_SECTIONS = setOf(BackupSection.BLOCKED_NUMBERS, BackupSection.WHITELIST)
    }
}
