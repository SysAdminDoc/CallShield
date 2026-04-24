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
    fun `Backup default version is 3`() {
        // v3 (v1.6.3): wildcard and keyword rules now carry schedule fields.
        // Older versions (v1, v2) are still accepted by the restore path
        // but writers always emit v3.
        val backup = Backup()
        assertEquals(3, backup.version)
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
        val bw = BackupWhitelist("2125551234", "Doctor's office", isEmergency = true)
        assertEquals("2125551234", bw.number)
        assertEquals("Doctor's office", bw.description)
        assertTrue(bw.isEmergency)
    }

    @Test
    fun `BackupWhitelist default emergency flag is false for older backups`() {
        val bw = BackupWhitelist("2125551234", "Doctor's office")
        assertFalse(bw.isEmergency)
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
    fun `Backup v3 with all sections populated`() {
        val backup = Backup(
            version = 3,
            blockedNumbers = listOf(
                BackupNumber("2125551234", "robocall", "Test", "user"),
                BackupNumber("3105559876", "telemarketer", "Sales", "community")
            ),
            whitelistNumbers = listOf(
                BackupWhitelist("5085551234", "Mom", isEmergency = true)
            ),
            wildcardRules = listOf(
                BackupWildcard("800*", false, "Toll-free", true)
            ),
            keywordRules = listOf(
                BackupKeyword("free money", false, "Spam phrase", true)
            )
        )
        assertEquals(3, backup.version)
        assertEquals(2, backup.blockedNumbers.size)
        assertEquals(1, backup.whitelistNumbers.size)
        assertTrue(backup.whitelistNumbers.single().isEmergency)
        assertEquals(1, backup.wildcardRules.size)
        assertEquals(1, backup.keywordRules.size)
    }

    @Test
    fun `Backup copy with modified version simulates v1`() {
        val current = Backup(
            blockedNumbers = listOf(BackupNumber("2125551234", "spam", "Test", "user"))
        )
        val v1 = current.copy(version = 1)
        assertEquals(1, v1.version)
        assertEquals(current.blockedNumbers, v1.blockedNumbers)
    }

    // ── v1.6.3: schedule fields on wildcard/keyword rules ──────────

    @Test
    fun `BackupWildcard default schedule fields are zero`() {
        // Pre-v3 backups don't carry schedule fields at all; the Kotlin
        // default of 0 must be interpreted downstream as "always active".
        val rule = BackupWildcard("800*", false, "Toll-free", true)
        assertEquals(0, rule.scheduleDays)
        assertEquals(0, rule.scheduleStartHour)
        assertEquals(0, rule.scheduleEndHour)
    }

    @Test
    fun `BackupWildcard carries schedule fields when present`() {
        val rule = BackupWildcard(
            pattern = "800*",
            isRegex = false,
            description = "Toll-free (business hours only)",
            enabled = true,
            scheduleDays = 0b0111110, // Mon–Fri
            scheduleStartHour = 9,
            scheduleEndHour = 17,
        )
        assertEquals(0b0111110, rule.scheduleDays)
        assertEquals(9, rule.scheduleStartHour)
        assertEquals(17, rule.scheduleEndHour)
    }

    @Test
    fun `BackupKeyword carries schedule fields`() {
        val kw = BackupKeyword(
            keyword = "auto warranty",
            caseSensitive = false,
            description = "",
            enabled = true,
            scheduleDays = 0b1111111,
            scheduleStartHour = 22,
            scheduleEndHour = 6,
        )
        assertEquals(0b1111111, kw.scheduleDays)
        assertEquals(22, kw.scheduleStartHour)
        assertEquals(6, kw.scheduleEndHour)
    }

    @Test
    fun `Backup copy with v2 version still parses`() {
        // The restore path accepts versions 1..3; emitting v2 must still
        // be valid (kept for defensive tests — a malicious downgrade
        // should not be rejected as "unsupported" since we still support
        // reading v2 backups from older installs).
        val downgraded = Backup().copy(version = 2)
        assertEquals(2, downgraded.version)
    }

    @Test
    fun `Backup data class supports destructuring`() {
        val backup = Backup(
            version = 3,
            app = "CallShield",
            timestamp = 1234567890L,
            blockedNumbers = emptyList(),
            whitelistNumbers = emptyList(),
            wildcardRules = emptyList(),
            keywordRules = emptyList()
        )
        val (version, app, timestamp) = backup
        assertEquals(3, version)
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
