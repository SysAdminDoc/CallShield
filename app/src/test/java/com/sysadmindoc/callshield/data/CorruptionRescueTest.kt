package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WildcardRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SQLite deletes a damaged database on the spot. The user's own blocks, allow
 * list and rules have no other copy on the phone, so they're read out of the
 * damaged file first and put into the empty one that replaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptionRescueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val damaged = IsolatedRepositoryFixture(context)
    private val rebuilt = IsolatedRepositoryFixture(context)

    @After
    fun tearDown() {
        damaged.close()
        rebuilt.close()
        File(context.noBackupFilesDir, "corruption-rescue.json").delete()
    }

    @Test
    fun `the empty database gets the user's blocks, allow list and rules back`() {
        seedUserRows()

        CorruptionRescue.save(context, damaged.sqlite)
        assertTrue(CorruptionRescue.isPending(context))
        assertTrue(importIntoRebuilt())

        val dao = rebuilt.dao
        val blocks = runBlocking { dao.getUserBlockedNumbers().first() }.associate { it.number to it.expiresAt }
        assertEquals(mapOf("+12125550101" to null, "+12125550102" to IN_A_WEEK), blocks)
        val allowed = runBlocking { dao.getAllWhitelist().first() }.single()
        assertEquals(Triple("+14155550100", "Dr. Patel's office", 2), Triple(allowed.number, allowed.description, allowed.rangeDigits))
        val wildcard = runBlocking { dao.getAllWildcardRules().first() }.single()
        assertEquals(Triple("+1832555*", false, 0b0011111), Triple(wildcard.pattern, wildcard.enabled, wildcard.scheduleDays))
        assertEquals(listOf("gift card" to true), runBlocking { dao.getAllKeywordRules().first() }.map { it.keyword to it.caseSensitive })
        assertEquals(listOf("+1646555####"), runBlocking { dao.getAllHashWildcardRules().first() }.map { it.pattern })
        assertFalse(CorruptionRescue.isPending(context))
    }

    @Test
    fun `a damaged table loses only its own rows`() {
        seedUserRows()
        damaged.damageTable("sms_keyword_rules")

        CorruptionRescue.save(context, damaged.sqlite)
        importIntoRebuilt()

        assertEquals(2, runBlocking { rebuilt.dao.getUserBlockedNumbers().first() }.size)
        assertEquals(1, runBlocking { rebuilt.dao.getAllWhitelist().first() }.size)
        assertEquals(1, runBlocking { rebuilt.dao.getAllWildcardRules().first() }.size)
        assertTrue(runBlocking { rebuilt.dao.getAllKeywordRules().first() }.isEmpty())
        assertEquals(1, runBlocking { rebuilt.dao.getAllHashWildcardRules().first() }.size)
    }

    @Test
    fun `a spam database row the user never blocked stays behind`() {
        runBlocking {
            damaged.dao.insertNumbers(listOf(SpamNumber(number = "+12125550177", type = "robocall", source = "github")))
        }

        CorruptionRescue.save(context, damaged.sqlite)
        importIntoRebuilt()

        assertTrue(runBlocking { rebuilt.dao.getUserBlockedNumbers().first() }.isEmpty())
        assertEquals(0, runBlocking { rebuilt.dao.getSpamCount() })
    }

    @Test
    fun `rows a start never put back are kept by the next rescue`() {
        runBlocking { damaged.repository.blockNumber("+12125550101") }
        CorruptionRescue.save(context, damaged.sqlite)
        // The process ended before they went back, and the next database broke too.
        val second = IsolatedRepositoryFixture(context)
        try {
            runBlocking { second.repository.blockNumber("+12125550199") }
            CorruptionRescue.save(context, second.sqlite)
        } finally {
            second.close()
        }

        importIntoRebuilt()

        assertEquals(
            setOf("+12125550101", "+12125550199"),
            runBlocking { rebuilt.dao.getUserBlockedNumbers().first() }.mapTo(HashSet()) { it.number },
        )
    }

    @Test
    fun `nothing saved means nothing to put back`() {
        assertFalse(importIntoRebuilt())

        CorruptionRescue.save(context, damaged.sqlite)
        assertTrue("an empty rescue is still cleared", importIntoRebuilt())
        assertFalse(CorruptionRescue.isPending(context))
    }

    private fun importIntoRebuilt() = runBlocking { CorruptionRescue.importPending(context, rebuilt.dao, rebuilt.repository) }

    private fun seedUserRows() =
        runBlocking {
            val repository = damaged.repository
            assertTrue(repository.blockNumber("+12125550101", "robocall", "kept calling"))
            assertTrue(repository.temporaryBlockNumber("+12125550102", IN_A_WEEK))
            assertTrue(repository.addToWhitelist("+14155550100", "Dr. Patel's office", rangeDigits = 2))
            damaged.dao.insertWildcardRule(WildcardRule(pattern = "+1832555*", enabled = false, scheduleDays = 0b0011111))
            damaged.dao.insertKeywordRule(SmsKeywordRule(keyword = "gift card", caseSensitive = true))
            damaged.dao.insertHashWildcardRule(HashWildcardRule(pattern = "+1646555####"))
        }

    private companion object {
        val IN_A_WEEK = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
    }
}
