package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.RestoreMode
import com.sysadmindoc.callshield.data.BackupRestore.RestorePayload
import com.sysadmindoc.callshield.data.BackupRestore.RestoreValidation
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A backup didn't carry when each number was blocked, so after a restore every
 * block had the restore's time (or none) and the Blocked list's Newest sort
 * lost its order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RestoreKeepsBlockTimesTest {
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
    fun `a backup round trip keeps when each number was blocked`() =
        runBlocking {
            val later = System.currentTimeMillis() + HOUR
            dao.insertNumber(SpamNumber(number = PERMANENT, type = "spam", source = "user", isUserBlocked = true, blockedAt = FIRST))
            dao.insertNumber(
                SpamNumber(number = TEMPORARY, type = "spam", source = "user", isUserBlocked = true, expiresAt = later, blockedAt = SECOND),
            )
            // A database number the user blocked keeps its row; the restore re-flags it.
            dao.insertNumber(SpamNumber(number = FROM_DATABASE, type = "robocall", source = "github", isUserBlocked = true, blockedAt = THIRD))
            val json = BackupRestore.backupToJson(BackupRestore.buildBackup(dao, repo, BLOCKED))

            val result = BackupRestore.restorePayload(context, validPayload(json), RestoreMode.REPLACE, dao, repo, BLOCKED)

            assertTrue(result.message, result.success)
            assertEquals(FIRST, dao.findByNumber(PERMANENT)?.blockedAt)
            assertEquals(SECOND, dao.findByNumber(TEMPORARY)?.blockedAt)
            assertEquals(later, dao.findByNumber(TEMPORARY)?.expiresAt)
            assertEquals(THIRD, dao.findByNumber(FROM_DATABASE)?.blockedAt)
            assertEquals(true, dao.findByNumber(FROM_DATABASE)?.isUserBlocked)
        }

    @Test
    fun `a backup from before block times were saved restores with the restore time`() =
        runBlocking {
            val olderBackup =
                """{"version":9,"app":"CallShield","timestamp":1,"blockedNumbers":[{"number":"$PERMANENT","type":"spam","description":"old"}]}"""
            val before = System.currentTimeMillis()

            val result = BackupRestore.restorePayload(context, validPayload(olderBackup), RestoreMode.MERGE, dao, repo, BLOCKED)

            assertTrue(result.message, result.success)
            val blockedAt = requireNotNull(dao.findByNumber(PERMANENT)?.blockedAt)
            assertTrue("$blockedAt is before the restore", blockedAt >= before)
            assertTrue("$blockedAt is after the restore", blockedAt <= System.currentTimeMillis())
        }

    @Test
    fun `a block time from the future restores as the restore time`() =
        runBlocking {
            val tomorrow = System.currentTimeMillis() + DAY
            val json = BackupRestore.backupToJson(Backup(blockedNumbers = listOf(BackupNumber(PERMANENT, "spam", "", blockedAt = tomorrow))))
            val payload = validPayload(json)
            assertNull(payload.blockedNumbers.single().blockedAt)

            val result = BackupRestore.restorePayload(context, payload, RestoreMode.MERGE, dao, repo, BLOCKED)

            assertTrue(result.message, result.success)
            assertTrue(requireNotNull(dao.findByNumber(PERMANENT)?.blockedAt) < tomorrow)
        }

    private fun validPayload(json: String): RestorePayload {
        val validation = BackupRestore.validateBackupJson(json)
        assertTrue("$validation", validation is RestoreValidation.Valid)
        return (validation as RestoreValidation.Valid).payload
    }

    private companion object {
        const val PERMANENT = "+12125550151"
        const val TEMPORARY = "+12125550152"
        const val FROM_DATABASE = "+12125550153"
        const val FIRST = 1_700_000_000_000L
        const val SECOND = 1_750_000_000_000L
        const val THIRD = 1_760_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        val BLOCKED = setOf(BackupSection.BLOCKED_NUMBERS)
    }
}
