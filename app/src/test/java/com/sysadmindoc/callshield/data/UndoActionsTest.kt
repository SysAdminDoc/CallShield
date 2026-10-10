package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each Undo puts back exactly what its action replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UndoActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private val repo get() = fixture.repository
    private val dao get() = fixture.dao
    private val later get() = System.currentTimeMillis() + 3_600_000L

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `undoing a temporary block puts back the allow it removed`() =
        runBlocking {
            repo.addToWhitelist(NUMBER, "pharmacy", expiresAt = later)

            val snapshot = requireNotNull(repo.temporaryDecisionUndoable(NUMBER, allow = false, expiresAt = later, type = "spam"))
            assertNull(dao.findWhitelistEntry(NUMBER))
            assertEquals(true, dao.findByNumber(NUMBER)?.isUserBlocked)

            repo.restoreDecision(snapshot)

            assertNull(dao.findByNumber(NUMBER))
            assertEquals("pharmacy", dao.findWhitelistEntry(NUMBER)?.description)
        }

    @Test
    fun `undoing a temporary allow puts back the temporary block it cleared`() =
        runBlocking {
            val expiry = later
            repo.temporaryBlockNumber(NUMBER, expiry, "spam", "their note")

            val snapshot = requireNotNull(repo.temporaryDecisionUndoable(NUMBER, allow = true, expiresAt = later))
            assertNotNull(dao.findWhitelistEntry(NUMBER))

            repo.restoreDecision(snapshot)

            assertNull(dao.findWhitelistEntry(NUMBER))
            val block = requireNotNull(dao.findByNumber(NUMBER))
            assertTrue(block.isUserBlocked)
            assertEquals("their note", block.description)
            assertEquals(expiry, block.expiresAt)
        }

    @Test
    fun `undoing a temporary allow puts back a synced spam row as it was`() =
        runBlocking {
            dao.insertNumber(SpamNumber(number = NUMBER, type = "robocall", description = "feed", source = "github"))
            val before = requireNotNull(dao.findByNumber(NUMBER))

            val snapshot = requireNotNull(repo.temporaryDecisionUndoable(NUMBER, allow = true, expiresAt = later))
            repo.restoreDecision(snapshot)

            assertEquals(before, dao.findByNumber(NUMBER))
            assertNull(dao.findWhitelistEntry(NUMBER))
        }

    @Test
    fun `a temporary allow a permanent block refuses offers nothing to undo`() =
        runBlocking {
            repo.blockNumber(NUMBER, "spam", "mine")

            assertNull(repo.temporaryDecisionUndoable(NUMBER, allow = true, expiresAt = later))
            assertEquals(true, dao.findByNumber(NUMBER)?.isUserBlocked)
            assertNull(dao.findWhitelistEntry(NUMBER))
        }

    @Test
    fun `undoing a cleared log puts every row back`() =
        runBlocking {
            dao.insertBlockedCall(BlockedCall(number = NUMBER, timestamp = 1_000L, type = "spam", logKey = "a"))
            dao.insertBlockedCall(BlockedCall(number = "+15559876543", timestamp = 2_000L, type = "robocall", isCall = false, smsBody = "win", logKey = "b"))
            val before = dao.getAllCallLogOnce().sortedBy { it.id }

            val cleared = repo.clearCallLogUndoable()
            assertTrue(dao.getAllCallLogOnce().isEmpty())
            assertEquals(before, cleared.sortedBy { it.id })

            repo.restoreCallLog(cleared)

            assertEquals(before, dao.getAllCallLogOnce().sortedBy { it.id })
        }

    @Test
    fun `undoing an area code block removes the rule it added`() =
        runBlocking {
            val undo = requireNotNull(repo.addWildcardRuleUndoable("+1212*", description = "New York"))
            assertEquals("New York", dao.findWildcardRule("+1212*")?.description)

            repo.undoWildcardRule(undo)

            assertNull(dao.findWildcardRule("+1212*"))
        }

    @Test
    fun `undoing an area code block puts back the rule with the same pattern`() =
        runBlocking {
            dao.insertWildcardRule(WildcardRule(pattern = "+1212*", description = "mine", enabled = false, scheduleDays = 0b0011111))
            val mine = requireNotNull(dao.findWildcardRule("+1212*"))

            val undo = requireNotNull(repo.addWildcardRuleUndoable("+1212*", description = "New York"))
            assertEquals("New York", dao.findWildcardRule("+1212*")?.description)

            repo.undoWildcardRule(undo)

            assertEquals(mine, dao.findWildcardRule("+1212*"))
            assertEquals(1, dao.getAllWildcardRules().first().size)
        }

    @Test
    fun `a repeat area code block changes nothing, so the first undo still removes it`() =
        runBlocking {
            val first = requireNotNull(repo.addWildcardRuleUndoable("+1212*", description = "New York"))
            val second = requireNotNull(repo.addWildcardRuleUndoable("+1212*", description = "again"))

            assertFalse(second.changed)
            assertEquals("New York", dao.findWildcardRule("+1212*")?.description)
            repo.undoWildcardRule(first)
            assertNull(dao.findWildcardRule("+1212*"))
        }

    @Test
    fun `undoing a replace restore puts back rows an import would refuse`() =
        runBlocking {
            val shortCode = repo.normalizeNumber("7726")
            assertTrue(repo.blockNumber(shortCode, "spam", "carrier short code"))
            dao.insertBlockedCall(BlockedCall(number = "", timestamp = 5_000L, type = "unknown", logKey = "hidden"))
            val sections = setOf(BackupRestore.BackupSection.BLOCKED_NUMBERS, BackupRestore.BackupSection.LOGS)
            val incoming = BackupRestore.RestorePayload(blockedNumbers = listOf(BackupRestore.BackupNumber("+15559876543", "spam", "theirs")))

            val result = BackupRestore.restoreWithUndo(context, incoming, BackupRestore.RestoreMode.REPLACE, dao, repo, sections)
            assertTrue(result.message, result.success)
            assertNull(dao.findByNumber(shortCode))
            assertTrue(dao.getAllCallLogOnce().isEmpty())

            BackupRestore.undoRestore(context, requireNotNull(result.undo), dao, repo)

            assertEquals("carrier short code", dao.findByNumber(shortCode)?.description)
            assertEquals(listOf("hidden"), dao.getAllCallLogOnce().map { it.logKey })
            assertNull(dao.findByNumber("+15559876543"))
        }

    @Test
    fun `undoing a replace restore puts back the rules and settings it cleared`() =
        runBlocking {
            repo.blockNumber(NUMBER, "spam", "kept")
            repo.addWildcardRule("+1900*", description = "mine")
            val incoming =
                BackupRestore.RestorePayload(
                    wildcardRules = listOf(BackupRestore.BackupWildcard("+1800*", isRegex = false, description = "theirs", enabled = true)),
                    settings = BackupRestore.BackupSettings(blockCallsEnabled = false),
                )

            val result = BackupRestore.restoreWithUndo(context, incoming, BackupRestore.RestoreMode.REPLACE, dao, repo, SECTIONS)
            assertTrue(result.message, result.success)
            assertNull(dao.findByNumber(NUMBER))
            assertNull(dao.findWildcardRule("+1900*"))
            assertFalse(repo.blockCallsEnabled.first())

            val undone = BackupRestore.undoRestore(context, requireNotNull(result.undo), dao, repo)

            assertTrue(undone.message, undone.success)
            assertEquals("kept", dao.findByNumber(NUMBER)?.description)
            assertEquals("mine", dao.findWildcardRule("+1900*")?.description)
            assertNull(dao.findWildcardRule("+1800*"))
            assertTrue(repo.blockCallsEnabled.first())
        }

    @Test
    fun `a replace restore adds the backup's lists and its undo takes them away`() =
        runBlocking {
            val id = ExternalBlocklistParser.idForUrl(MY_LIST)
            fixture.settingsStore.edit {
                it[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS] =
                    """[{"id":"$id","label":"Mine","url":"$MY_LIST","enabled":true,"lastSyncedAt":1}]"""
            }
            val incoming =
                BackupRestore.RestorePayload(
                    settings =
                        BackupRestore.BackupSettings(
                            externalBlocklists = listOf(BackupRestore.BackupExternalBlocklist(THEIR_LIST, "Theirs")),
                        ),
                )

            val result = BackupRestore.restoreWithUndo(context, incoming, BackupRestore.RestoreMode.REPLACE, dao, repo, SECTIONS)
            assertTrue(result.message, result.success)
            assertEquals(setOf(MY_LIST, THEIR_LIST), listUrls())

            val undone = BackupRestore.undoRestore(context, requireNotNull(result.undo), dao, repo)

            assertTrue(undone.message, undone.success)
            assertEquals(setOf(MY_LIST), listUrls())
            assertEquals(
                1L,
                fixture.settingsStore.data
                    .first()
                    .externalBlocklistSubscriptions()
                    .single()
                    .lastSyncedAt,
            )
        }

    private suspend fun listUrls(): Set<String> =
        fixture.settingsStore.data
            .first()
            .externalBlocklistSubscriptions()
            .map { it.url }
            .toSet()

    @Test
    fun `a merge restore keeps no undo`() =
        runBlocking {
            val incoming =
                BackupRestore.RestorePayload(
                    wildcardRules = listOf(BackupRestore.BackupWildcard("+1800*", isRegex = false, description = "theirs", enabled = true)),
                )

            val result = BackupRestore.restoreWithUndo(context, incoming, BackupRestore.RestoreMode.MERGE, dao, repo, SECTIONS)

            assertTrue(result.success)
            assertNull(result.undo)
        }

    private companion object {
        const val NUMBER = "+15552345678"
        const val MY_LIST = "https://lists.example/mine.txt"
        const val THEIR_LIST = "https://lists.example/theirs.txt"
        val SECTIONS =
            setOf(
                BackupRestore.BackupSection.BLOCKED_NUMBERS,
                BackupRestore.BackupSection.WILDCARD_RULES,
                BackupRestore.BackupSection.SETTINGS,
            )
    }
}
