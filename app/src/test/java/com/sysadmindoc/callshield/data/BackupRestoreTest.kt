package com.sysadmindoc.callshield.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupKeyword
import com.sysadmindoc.callshield.data.BackupRestore.BackupLogEntry
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupRangeRule
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.BackupWildcard
import com.sysadmindoc.callshield.data.BackupRestore.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for BackupRestore — backup data model serialization.
 * Tests the data classes and structure; actual restore requires Android Context
 * so we test the model/contract side here.
 */
class BackupRestoreTest {
    // ── Backup data class defaults ───────────────────────────────────────

    @Test
    fun `Backup default version is 9`() {
        // v9 preserves privacy-safe pipeline diagnostics. Older versions remain accepted.
        val backup = Backup()
        assertEquals(9, backup.version)
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
        assertTrue(backup.rangeRules.isEmpty())
        assertNull(backup.settings)
        assertTrue(backup.logs.isEmpty())
    }

    @Test
    fun `Backup timestamp is set automatically`() {
        val before = System.currentTimeMillis()
        val backup = Backup()
        val after = System.currentTimeMillis()
        assertTrue(backup.timestamp in before..after)
    }

    // ── BackupWhitelist data class ───────────────────────────────────────

    @Test
    fun `BackupWhitelist default emergency flag is false for older backups`() {
        val bw = BackupWhitelist("2125551234", "Doctor's office")
        assertFalse(bw.isEmergency)
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

    @Test
    fun `restore validation rejects wrong app backups`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    app = "OtherApp",
                    blockedNumbers = listOf(validBackupNumber()),
                ),
            )

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.WRONG_APP)
    }

    @Test
    fun `restore validation rejects unsupported backup versions`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    version = 99,
                    blockedNumbers = listOf(validBackupNumber()),
                ),
            )

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.UNSUPPORTED_VERSION)
    }

    @Test
    fun `restore validation rejects empty backups`() {
        val validation = BackupRestore.validateBackupForRestore(Backup())

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.EMPTY)
    }

    @Test
    fun `restore validation rejects aggregate rows past the import cap`() {
        val repeatedNumber = validBackupNumber()
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers = List(BackupRestore.MAX_BACKUP_RESTORE_ROWS / 2 + 1) { repeatedNumber },
                    logs =
                        List(BackupRestore.MAX_BACKUP_RESTORE_ROWS / 2) {
                            BackupLogEntry("2125551234", timestamp = it.toLong())
                        },
                ),
            )

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.TOO_MANY_ITEMS)
    }

    @Test
    fun `restore validation rejects backups with no valid items`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers = listOf(BackupNumber("12", "spam", "too short")),
                    whitelistNumbers = listOf(BackupWhitelist("abc", "not a number")),
                    wildcardRules = listOf(BackupWildcard(" ", isRegex = false, description = "", enabled = true)),
                    keywordRules = listOf(BackupKeyword(" ", caseSensitive = false, description = "", enabled = true)),
                ),
            )

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.NO_VALID_ITEMS)
    }

    @Test
    fun `restore validation sanitizes valid payload before mutation`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers = listOf(BackupNumber(" (212) 555-1234 ", "", "  Sales  ")),
                    whitelistNumbers = listOf(BackupWhitelist("+1 303 555 0100", "  Clinic  ", isEmergency = true)),
                    wildcardRules =
                        listOf(
                            BackupWildcard(
                                pattern = " 800* ",
                                isRegex = false,
                                description = "  Toll free  ",
                                enabled = true,
                                scheduleDays = 0b111111111,
                                scheduleStartHour = -4,
                                scheduleEndHour = 42,
                            ),
                        ),
                    keywordRules =
                        listOf(
                            BackupKeyword(
                                keyword = "  verify now ",
                                caseSensitive = true,
                                description = "  Phish  ",
                                enabled = false,
                                scheduleDays = 0b1111111,
                                scheduleStartHour = 22,
                                scheduleEndHour = 6,
                            ),
                        ),
                ),
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertEquals(BackupRestore.RestoreCounts(1, 1, 1, 1), payload.counts)
        assertEquals("2125551234", payload.blockedNumbers.single().number)
        assertEquals("unknown", payload.blockedNumbers.single().type)
        assertEquals("Sales", payload.blockedNumbers.single().description)
        assertEquals("+13035550100", payload.whitelistNumbers.single().number)
        assertEquals("Clinic", payload.whitelistNumbers.single().description)
        assertEquals(0b1111111, payload.wildcardRules.single().scheduleDays)
        assertEquals(0, payload.wildcardRules.single().scheduleStartHour)
        assertEquals(23, payload.wildcardRules.single().scheduleEndHour)
        assertEquals("verify now", payload.keywordRules.single().keyword)
        assertFalse(payload.keywordRules.single().enabled)
    }

    // ── Backup format with populated data ─────────────────────────────────

    @Test
    fun `Backup v5 with all sections populated`() {
        val backup =
            Backup(
                version = 5,
                blockedNumbers =
                    listOf(
                        BackupNumber("2125551234", "robocall", "Test"),
                        BackupNumber("3105559876", "telemarketer", "Sales"),
                    ),
                whitelistNumbers =
                    listOf(
                        BackupWhitelist("5085551234", "Mom", isEmergency = true),
                    ),
                wildcardRules =
                    listOf(
                        BackupWildcard("800*", false, "Toll-free", true),
                    ),
                keywordRules =
                    listOf(
                        BackupKeyword("free money", false, "Spam phrase", true),
                    ),
                rangeRules = listOf(BackupRangeRule("+1555#######", "Range", true)),
                settings = BackupSettings(blockCallsEnabled = false),
                logs = listOf(BackupLogEntry("+15551234567", timestamp = 1000L, matchReason = "test")),
            )
        assertEquals(5, backup.version)
        assertEquals(2, backup.blockedNumbers.size)
        assertEquals(1, backup.whitelistNumbers.size)
        assertTrue(backup.whitelistNumbers.single().isEmergency)
        assertEquals(1, backup.wildcardRules.size)
        assertEquals(1, backup.keywordRules.size)
        assertEquals(1, backup.rangeRules.size)
        assertNotNull(backup.settings)
        assertEquals(1, backup.logs.size)
    }

    @Test
    fun `Backup copy with modified version simulates v1`() {
        val current =
            Backup(
                blockedNumbers = listOf(BackupNumber("2125551234", "spam", "Test")),
            )
        val v1 = current.copy(version = 1)
        assertEquals(1, v1.version)
        assertEquals(current.blockedNumbers, v1.blockedNumbers)
    }

    @Test
    fun `restore validation preserves active temporary decisions and supported screening sources`() {
        val futureExpiry = System.currentTimeMillis() + 60_000L
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers =
                        listOf(
                            BackupNumber("2125551234", "spam", "Temporary block", futureExpiry),
                        ),
                    whitelistNumbers =
                        listOf(
                            BackupWhitelist("3035550100", "Temporary allow", expiresAt = futureExpiry),
                        ),
                    settings =
                        BackupSettings(
                            categoryCallActions =
                                listOf(
                                    "scam=silence",
                                    "scam=silence",
                                    "unknown=allow",
                                    "robocall=invalid",
                                ),
                            selectedContactGroups = listOf("b".repeat(64), "raw group", "b".repeat(64)),
                            notificationScreeningPackages =
                                listOf(
                                    " com.google.android.gm ",
                                    "unsupported.package",
                                    "com.google.android.gm",
                                ),
                        ),
                ),
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertEquals(futureExpiry, payload.blockedNumbers.single().expiresAt)
        assertEquals(futureExpiry, payload.whitelistNumbers.single().expiresAt)
        assertEquals(
            listOf("com.google.android.gm"),
            payload.settings?.notificationScreeningPackages,
        )
        assertEquals(listOf("scam=silence"), payload.settings?.categoryCallActions)
        assertEquals(listOf("b".repeat(64)), payload.settings?.selectedContactGroups)
    }

    @Test
    fun `restore validation drops expired temporary decisions`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers =
                        listOf(
                            BackupNumber("2125551234", "spam", "Expired block", expiresAt = 1L),
                        ),
                    whitelistNumbers =
                        listOf(
                            BackupWhitelist("3035550100", "Expired allow", expiresAt = 1L),
                        ),
                ),
            )

        assertInvalidRestore(validation, BackupRestore.RestoreFailure.NO_VALID_ITEMS)
    }

    @Test
    fun `restore validation preserves the follow-defaults screening sentinel`() {
        // null means "user never customized — follow future catalog defaults".
        // It must survive validation as null, NOT be resolved into a concrete
        // list, or restored users would have the default set pinned forever.
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(settings = BackupSettings()),
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertNull(payload.settings?.notificationScreeningPackages)
    }

    @Test
    fun `restore validation preserves an explicit empty screening source selection`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    settings = BackupSettings(notificationScreeningPackages = emptyList()),
                ),
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertEquals(emptyList<String>(), payload.settings?.notificationScreeningPackages)
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
        val rule =
            BackupWildcard(
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
        val kw =
            BackupKeyword(
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
        val backup =
            Backup(
                version = 3,
                app = "CallShield",
                timestamp = 1234567890L,
                blockedNumbers = emptyList(),
                whitelistNumbers = emptyList(),
                wildcardRules = emptyList(),
                keywordRules = emptyList(),
            )
        val (version, app, timestamp) = backup
        assertEquals(3, version)
        assertEquals("CallShield", app)
        assertEquals(1234567890L, timestamp)
    }

    @Test
    fun `BackupNumber with empty strings`() {
        val bn = BackupNumber("", "", "")
        assertEquals("", bn.number)
        assertEquals("", bn.type)
    }

    @Test
    fun `Backup with large blocked list`() {
        val numbers =
            (1..100).map {
                BackupNumber("212555${it.toString().padStart(4, '0')}", "spam", "Entry $it")
            }
        val backup = Backup(blockedNumbers = numbers)
        assertEquals(100, backup.blockedNumbers.size)
    }

    @Test
    fun `restore validation filters to selected sections`() {
        val validation =
            BackupRestore.validateBackupForRestore(
                Backup(
                    blockedNumbers = listOf(validBackupNumber()),
                    rangeRules = listOf(BackupRangeRule(" +1555####### ", "  Range  ", true)),
                    settings = BackupSettings(blockCallsEnabled = false),
                    logs = listOf(BackupLogEntry("(212) 555-0000", timestamp = -1L, confidence = 150)),
                ),
                sections = setOf(BackupSection.RANGE_RULES, BackupSection.LOGS),
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val payload = (validation as BackupRestore.RestoreValidation.Valid).payload
        assertEquals(BackupRestore.RestoreCounts(rangeRules = 1, logs = 1), payload.counts)
        assertTrue(payload.blockedNumbers.isEmpty())
        assertNull(payload.settings)
        assertEquals("+1555#######", payload.rangeRules.single().pattern)
        assertEquals("2125550000", payload.logs.single().number)
        assertEquals(0L, payload.logs.single().timestamp)
        assertEquals(100, payload.logs.single().confidence)
    }

    private fun validBackupNumber(): BackupNumber = BackupNumber("2125551234", "spam", "Test")

    private fun assertInvalidRestore(
        validation: BackupRestore.RestoreValidation,
        failure: BackupRestore.RestoreFailure,
    ) {
        assertTrue(validation is BackupRestore.RestoreValidation.Invalid)
        assertEquals(failure, (validation as BackupRestore.RestoreValidation.Invalid).failure)
    }

    // ── Moshi round-trip ─────────────────────────────────────────────────

    @Test
    fun `backup payload survives a Moshi round-trip with every section populated`() {
        // Replaces a set of tests that only asserted compiler-generated
        // constructor/equals behaviour. This exercises what a restore actually
        // depends on: that every payload class serializes and reads back
        // identically through the same reflective adapter the app uses (the
        // path R8 can silently break — see proguard-rules.pro).
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(Backup::class.java)

        val original =
            Backup(
                timestamp = 1_720_000_000_000L,
                blockedNumbers = listOf(BackupNumber("+12125551234", "robocall", "Spam caller")),
                whitelistNumbers = listOf(BackupWhitelist("+13105550100", "Doctor", isEmergency = true)),
                wildcardRules = listOf(BackupWildcard("800*", false, "Block toll-free", true)),
                keywordRules = listOf(BackupKeyword("free money", false, "Common spam", true)),
                rangeRules = listOf(BackupRangeRule("+1312555####", "Range", true)),
                settings = BackupSettings(),
                logs = listOf(BackupLogEntry("+14155550111", 1_720_000_000_000L, "database", true)),
            )

        val restored = adapter.fromJson(adapter.toJson(original))

        assertNotNull("round-trip must not produce null", restored)
        assertEquals(original, restored)
    }
}
