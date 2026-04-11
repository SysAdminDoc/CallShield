package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.BackupWildcard
import com.sysadmindoc.callshield.data.BackupRestore.BackupKeyword
import com.sysadmindoc.callshield.data.BackupRestore.RestoreResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BackupRestore — backup data model serialization.
 * Tests the data classes and structure; actual restore requires Android Context
 * so we test the model/contract side here.
 */
class BackupRestoreTest {

    // ── Backup data class defaults ───────────────────────────────────────

    @Test
    fun `Backup default version is 2`() {
        val backup = Backup()
        assertEquals(2, backup.version)
    }

    @Test
    fun `Backup default app name is CallShield`() {
        val backup = Backup()
        assertEquals("CallShield", backup.app)
    }

    @Test
    fun `Backup default lists are empty`() {
        val backup = Backup()
        assertTrue(backup.blockedNumbers.isEmpty())
        assertTrue(backup.whitelistNumbers.isEmpty())
        assertTrue(backup.wildcardRules.isEmpty())
        assertTrue(backup.keywordRules.isEmpty())
    }

    @Test
    fun `Backup timestamp is set automatically`() {
        val before = System.currentTimeMillis()
        val backup = Backup()
        val after = System.currentTimeMillis()
        assertTrue(backup.timestamp in before..after)
    }

    // ── BackupNumber data class ──────────────────────────────────────────

    @Test
    fun `BackupNumber stores all fields`() {
        val bn = BackupNumber("2125551234", "robocall", "Spam caller", "user")
        assertEquals("2125551234", bn.number)
        assertEquals("robocall", bn.type)
        assertEquals("Spam caller", bn.description)
        assertEquals("user", bn.source)
    }

    @Test
    fun `BackupNumber equality`() {
        val a = BackupNumber("2125551234", "robocall", "Spam", "user")
        val b = BackupNumber("2125551234", "robocall", "Spam", "user")
        assertEquals(a, b)
    }

    @Test
    fun `BackupNumber inequality on different number`() {
        val a = BackupNumber("2125551234", "robocall", "Spam", "user")
        val b = BackupNumber("3105551234", "robocall", "Spam", "user")
        assertNotEquals(a, b)
    }

    // ── BackupWhitelist data class ───────────────────────────────────────

    @Test
    fun `BackupWhitelist stores fields`() {
        val bw = BackupWhitelist("2125551234", "Doctor's office")
        assertEquals("2125551234", bw.number)
        assertEquals("Doctor's office", bw.description)
    }

    // ── BackupWildcard data class ────────────────────────────────────────

    @Test
    fun `BackupWildcard stores all fields`() {
        val rule = BackupWildcard("800*", false, "Block all toll-free", true)
        assertEquals("800*", rule.pattern)
        assertFalse(rule.isRegex)
        assertEquals("Block all toll-free", rule.description)
        assertTrue(rule.enabled)
    }

    @Test
    fun `BackupWildcard regex rule`() {
        val rule = BackupWildcard("^800\\d{7}$", true, "Toll-free regex", true)
        assertTrue(rule.isRegex)
    }

    @Test
    fun `BackupWildcard disabled rule`() {
        val rule = BackupWildcard("555*", false, "Test", false)
        assertFalse(rule.enabled)
    }

    // ── BackupKeyword data class ─────────────────────────────────────────

    @Test
    fun `BackupKeyword stores all fields`() {
        val kw = BackupKeyword("free money", false, "Common spam", true)
        assertEquals("free money", kw.keyword)
        assertFalse(kw.caseSensitive)
        assertEquals("Common spam", kw.description)
        assertTrue(kw.enabled)
    }

    @Test
    fun `BackupKeyword case sensitive`() {
        val kw = BackupKeyword("FREE", true, "Case-sensitive test", true)
        assertTrue(kw.caseSensitive)
    }

    // ── RestoreResult data class ─────────────────────────────────────────

    @Test
    fun `RestoreResult success`() {
        val r = RestoreResult(true, "Restored 5 numbers")
        assertTrue(r.success)
        assertEquals("Restored 5 numbers", r.message)
    }

    @Test
    fun `RestoreResult failure`() {
        val r = RestoreResult(false, "Invalid backup format")
        assertFalse(r.success)
        assertEquals("Invalid backup format", r.message)
    }

    // ── Backup v2 format with populated data ─────────────────────────────

    @Test
    fun `Backup v2 with all sections populated`() {
        val backup = Backup(
            version = 2,
            blockedNumbers = listOf(
                BackupNumber("2125551234", "robocall", "Test", "user"),
                BackupNumber("3105559876", "telemarketer", "Sales", "community")
            ),
            whitelistNumbers = listOf(
                BackupWhitelist("5085551234", "Mom")
            ),
            wildcardRules = listOf(
                BackupWildcard("800*", false, "Toll-free", true)
            ),
            keywordRules = listOf(
                BackupKeyword("free money", false, "Spam phrase", true)
            )
        )
        assertEquals(2, backup.version)
        assertEquals(2, backup.blockedNumbers.size)
        assertEquals(1, backup.whitelistNumbers.size)
        assertEquals(1, backup.wildcardRules.size)
        assertEquals(1, backup.keywordRules.size)
    }

    @Test
    fun `Backup copy with modified version simulates v1`() {
        val v2 = Backup(
            blockedNumbers = listOf(BackupNumber("2125551234", "spam", "Test", "user"))
        )
        val v1 = v2.copy(version = 1)
        assertEquals(1, v1.version)
        assertEquals(v2.blockedNumbers, v1.blockedNumbers)
    }

    @Test
    fun `Backup data class supports destructuring`() {
        val backup = Backup(
            version = 2,
            app = "CallShield",
            timestamp = 1234567890L,
            blockedNumbers = emptyList(),
            whitelistNumbers = emptyList(),
            wildcardRules = emptyList(),
            keywordRules = emptyList()
        )
        val (version, app, timestamp) = backup
        assertEquals(2, version)
        assertEquals("CallShield", app)
        assertEquals(1234567890L, timestamp)
    }

    @Test
    fun `BackupNumber with empty strings`() {
        val bn = BackupNumber("", "", "", "")
        assertEquals("", bn.number)
        assertEquals("", bn.type)
    }

    @Test
    fun `Backup with large blocked list`() {
        val numbers = (1..100).map {
            BackupNumber("212555${it.toString().padStart(4, '0')}", "spam", "Entry $it", "user")
        }
        val backup = Backup(blockedNumbers = numbers)
        assertEquals(100, backup.blockedNumbers.size)
    }
}
