package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.RestoreMode
import com.sysadmindoc.callshield.data.BackupRestore.RestoreValidation
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A bank's published line never calls out, so a call showing it is spoofed.
 * The mark blocks that number's calls ahead of everything else, keeps its
 * texts and the user's own outgoing calls alone, and travels with backups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InboundOnlyNumbersTest {
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
    fun `a mark made in one spelling matches the number in every spelling`() =
        runBlocking {
            val mark = repo.markInboundOnly("(212) 555-0150", "Chase card line", addedAt = 1_000L)

            assertEquals(BANK, mark?.number)
            assertEquals("Chase card line", repo.findInboundOnly(BANK)?.description)
            assertEquals(BANK, repo.findInboundOnly("212-555-0150")?.number)
            assertEquals(BANK, repo.findInboundOnly("12125550150")?.number)
            assertNull(repo.findInboundOnly(OTHER))
        }

    @Test
    fun `short codes and emergency numbers can't be marked`() =
        runBlocking {
            assertNull(repo.markInboundOnly("911"))
            assertNull(repo.markInboundOnly("72975"))
            assertNull(repo.findInboundOnly("911"))
            assertTrue(dao.getInboundOnlyNumbers().isEmpty())
        }

    @Test
    fun `unmarking hands back the row, and Undo puts back its note and date`() =
        runBlocking {
            repo.markInboundOnly(BANK, "card line", addedAt = 1_000L)

            val removed = repo.unmarkInboundOnly("212-555-0150")
            assertNull(repo.findInboundOnly(BANK))

            repo.restoreInboundOnly(requireNotNull(removed))
            val back = repo.findInboundOnly(BANK)
            assertEquals("card line", back?.description)
            assertEquals(1_000L, back?.addedAt)
        }

    @Test
    fun `a marked number is spam for a call but not for a text`() =
        runBlocking {
            repo.markInboundOnly(BANK, "card line")

            val call = repo.isSpam(BANK)
            assertTrue(call.isSpam)
            assertEquals(BlockReasonCode.INBOUND_ONLY, call.reasonCode)
            assertEquals("card line", call.description)

            val text = repo.isSpamSms(BANK, "Your statement is ready")
            assertNotEquals(BlockReasonCode.INBOUND_ONLY, text.reasonCode)
        }

    @Test
    fun `a backup carries the mark and a restore puts it back`() =
        runBlocking {
            repo.markInboundOnly(BANK, "card line", addedAt = 1_000L)
            val json = BackupRestore.backupToJson(BackupRestore.buildBackup(dao, repo, setOf(BackupSection.BLOCKED_NUMBERS)))
            dao.clearInboundOnly()

            val payload = validPayload(json)
            assertEquals(1, payload.counts.blockedNumbers)
            val result = BackupRestore.restorePayload(context, payload, RestoreMode.MERGE, dao, repo, setOf(BackupSection.BLOCKED_NUMBERS))

            assertTrue(result.message, result.success)
            val back = repo.findInboundOnly(BANK)
            assertEquals("card line", back?.description)
            assertEquals(1_000L, back?.addedAt)
        }

    @Test
    fun `a merge keeps this phone's note, and a replace drops marks the backup lacks`() =
        runBlocking {
            repo.markInboundOnly(BANK, "from the backup")
            val json = BackupRestore.backupToJson(BackupRestore.buildBackup(dao, repo, setOf(BackupSection.BLOCKED_NUMBERS)))
            repo.markInboundOnly(BANK, "edited here")
            repo.markInboundOnly(OTHER, "only here")

            BackupRestore.restorePayload(context, validPayload(json), RestoreMode.MERGE, dao, repo, setOf(BackupSection.BLOCKED_NUMBERS))
            assertEquals("edited here", repo.findInboundOnly(BANK)?.description)
            assertEquals("only here", repo.findInboundOnly(OTHER)?.description)

            BackupRestore.restorePayload(context, validPayload(json), RestoreMode.REPLACE, dao, repo, setOf(BackupSection.BLOCKED_NUMBERS))
            assertEquals("from the backup", repo.findInboundOnly(BANK)?.description)
            assertNull(repo.findInboundOnly(OTHER))
        }

    @Test
    fun `an imported mark for a short or emergency number is dropped`() {
        val json =
            BackupRestore.backupToJson(
                BackupRestore.Backup(
                    inboundOnlyNumbers =
                        listOf(
                            BackupRestore.BackupInboundOnly("911"),
                            BackupRestore.BackupInboundOnly("+1 (212) 555-0150", "  card line  "),
                        ),
                ),
            )

        val marks = validPayload(json).inboundOnlyNumbers

        assertEquals(listOf(BANK to "card line"), marks.map { it.number to it.description })
    }

    private fun validPayload(json: String) =
        when (val validation = BackupRestore.validateBackupJson(json, setOf(BackupSection.BLOCKED_NUMBERS))) {
            is RestoreValidation.Valid -> validation.payload
            is RestoreValidation.Invalid -> throw AssertionError("backup refused: ${validation.failure}")
        }

    private companion object {
        const val BANK = "+12125550150"
        const val OTHER = "+12125550151"
    }
}
