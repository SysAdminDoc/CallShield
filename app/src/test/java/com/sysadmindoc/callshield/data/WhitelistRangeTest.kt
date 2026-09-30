package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A doctor's office that calls from (555) 234-5601 one day and -5617 the
 * next used to need every line allowed by hand. An entry can now cover the
 * numbers that differ from it only in their last 2 or 3 digits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhitelistRangeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private val repository get() = fixture.repository

    @Before
    fun setUp() {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")
        fixture = IsolatedRepositoryFixture(context)
        runBlocking {
            // Every number in the office's 555-234-5xxx thousand is on the spam
            // database, so an allow is the only thing that lets one through.
            fixture.dao.insertNumbers(
                listOf(OFFICE, SIBLING_2, SIBLING_3, OUTSIDE, LONGER).map { SpamNumber(number = it, type = "robocall", reports = 9, source = "github") },
            )
            repository.addToWhitelist(OFFICE, "Dr. Lee")
        }
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `an entry allows its exact number only until a block is picked`() =
        runBlocking {
            assertEquals(0, entry().rangeDigits)
            assertEquals("manual_whitelist", repository.isSpam(OFFICE).matchSource)
            assertEquals("database", repository.isSpam(SIBLING_2).matchSource)
        }

    @Test
    fun `a two-digit block lets the numbers that share all but the last 2 digits through`() =
        runBlocking {
            assertTrue(repository.setWhitelistRange(entry().id, 2))

            val sibling = repository.isSpam(SIBLING_2)
            assertFalse(sibling.isSpam)
            assertEquals("manual_whitelist", sibling.matchSource)
            assertEquals(entry().id, sibling.ruleId)
            // The national spelling of a sibling is covered too.
            assertEquals("manual_whitelist", repository.isSpam("5552345601").matchSource)
            // One digit further left is outside the block.
            assertEquals("database", repository.isSpam(SIBLING_3).matchSource)
            assertEquals("database", repository.isSpam(OUTSIDE).matchSource)
            // Same leading digits, one digit longer: not part of it.
            assertEquals("database", repository.isSpam(LONGER).matchSource)
        }

    @Test
    fun `a three-digit block reaches one digit further`() =
        runBlocking {
            assertTrue(repository.setWhitelistRange(entry().id, 3))

            assertEquals("manual_whitelist", repository.isSpam(SIBLING_2).matchSource)
            assertEquals("manual_whitelist", repository.isSpam(SIBLING_3).matchSource)
            assertEquals("database", repository.isSpam(OUTSIDE).matchSource)
        }

    @Test
    fun `turning the block off goes back to the exact number`() =
        runBlocking {
            repository.setWhitelistRange(entry().id, 2)
            assertTrue(repository.setWhitelistRange(entry().id, 0))

            assertEquals("database", repository.isSpam(SIBLING_2).matchSource)
            assertEquals("manual_whitelist", repository.isSpam(OFFICE).matchSource)
        }

    @Test
    fun `a number the user blocked by itself stays blocked inside the block`() =
        runBlocking {
            repository.setWhitelistRange(entry().id, 2)
            repository.blockNumber(SIBLING_2, "spam", "not the office")

            assertEquals("user_blocklist", repository.isSpam(SIBLING_2).matchSource)
            assertFalse(repository.hasActiveWhitelistEntry(SIBLING_2))
            assertEquals("manual_whitelist", repository.isSpam("+15552345602").matchSource)
        }

    @Test
    fun `an emergency entry's block comes through as an emergency contact`() =
        runBlocking {
            repository.setWhitelistEmergency(entry().id, true)
            repository.setWhitelistRange(entry().id, 2)

            assertEquals("emergency_contact", repository.isSpam(SIBLING_2).matchSource)
            assertTrue(repository.hasActiveWhitelistEntry(SIBLING_2))
        }

    @Test
    fun `a temporary allow, a short number or an odd size can't cover a block`() =
        runBlocking {
            assertFalse("only 2 or 3 digits", repository.setWhitelistRange(entry().id, 4))
            assertFalse(repository.setWhitelistRange(entry().id, 1))

            repository.addToWhitelist(TEMPORARY, expiresAt = System.currentTimeMillis() + HOUR)
            val temporary = fixture.dao.findWhitelistEntry(TEMPORARY)!!
            assertFalse(repository.setWhitelistRange(temporary.id, 2))

            repository.addToWhitelist("24680")
            val shortCode = fixture.dao.findWhitelistEntry("24680")!!
            assertFalse(repository.setWhitelistRange(shortCode.id, 2))

            assertEquals(listOf(0, 0, 0), listOf(entry(), temporary, shortCode).map { fixture.dao.findWhitelistEntryById(it.id)!!.rangeDigits })
        }

    @Test
    fun `allowing the number again keeps its block, and a restore can set one`() =
        runBlocking {
            repository.setWhitelistRange(entry().id, 3)

            repository.addToWhitelist(OFFICE, "Dr. Lee, front desk")
            assertEquals(3, entry().rangeDigits)

            repository.addToWhitelist(OFFICE, "Dr. Lee", rangeDigits = 2)
            assertEquals(2, entry().rangeDigits)

            // A temporary allow never covers a block, whatever it's handed.
            repository.addToWhitelist(TEMPORARY, expiresAt = System.currentTimeMillis() + HOUR, rangeDigits = 2)
            assertEquals(0, fixture.dao.findWhitelistEntry(TEMPORARY)!!.rangeDigits)
        }

    @Test
    fun `a backup carries the block and restoring it brings the block back`() =
        runBlocking {
            repository.setWhitelistRange(entry().id, 2)
            val sections = setOf(BackupRestore.BackupSection.WHITELIST)
            val json = BackupRestore.backupToJson(BackupRestore.buildBackup(fixture.dao, repository, sections))
            fixture.dao.clearWhitelist()

            val validation = BackupRestore.validateBackupJson(json)
            val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
            assertEquals(2, payload.whitelistNumbers.single().rangeDigits)
            val result = BackupRestore.restorePayload(context, payload, BackupRestore.RestoreMode.MERGE, fixture.dao, repository, sections)

            assertTrue(result.message, result.success)
            assertEquals(2, entry().rangeDigits)
            assertEquals("manual_whitelist", repository.isSpam(SIBLING_2).matchSource)
        }

    @Test
    fun `a backup with a size the app doesn't offer restores the exact number`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                BackupRestore.Backup(whitelistNumbers = listOf(BackupRestore.BackupWhitelist(OFFICE, "Dr. Lee", rangeDigits = 6))),
            )

        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertEquals(0, payload.whitelistNumbers.single().rangeDigits)
    }

    @Test
    fun `the list shows the block with its last digits as X`() {
        assertEquals("⁦(555) 234-56XX⁩", PhoneFormatter.formatBlockIsolated(OFFICE, 2))
        assertEquals("⁦(555) 234-5XXX⁩", PhoneFormatter.formatBlockIsolated(OFFICE, 3))
    }

    private suspend fun entry() =
        fixture.dao
            .getAllWhitelist()
            .first()
            .single { it.number == OFFICE }

    private companion object {
        const val OFFICE = "+15552345678"
        const val SIBLING_2 = "+15552345601"
        const val SIBLING_3 = "+15552345001"
        const val OUTSIDE = "+15552344678"
        const val LONGER = "+155523456011"
        const val TEMPORARY = "+15553335678"
        const val HOUR = 60L * 60L * 1000L
    }
}
