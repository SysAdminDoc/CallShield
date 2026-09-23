package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val migration11To12 = phoneIdentityMigration(PhoneIdentityCanonicalizer("US"))

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrateFromVersion5ToCurrentValidatesSchemaAndDefaults() {
        LegacyDatabases.create(context, TEST_DB, version = 5)

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt("SELECT COUNT(*) FROM spam_numbers", 1)
        db.assertSingleInt("SELECT isEmergency FROM whitelist WHERE number = '+15550000002'", 0)
        db.assertSingleInt("SELECT scheduleDays FROM wildcard_rules WHERE pattern = '+1555*'", 0)
        db.assertSingleInt("SELECT scheduleStartHour FROM sms_keyword_rules WHERE keyword = 'prize'", 0)
        db.assertHasColumn("hash_wildcard_rules", "scheduleEndHour")
        db.assertHasColumn("call_log", "logKey")
        db.assertHasColumn("call_log", "reasonCode")
        db.assertHasColumn("call_log", "ruleId")
        db.assertHasColumn("pending_blocked_call_logs", "idempotencyKey")
        db.assertHasColumn("pending_blocked_call_logs", "reasonCode")
        db.assertHasColumn("pending_blocked_call_logs", "ruleId")
        db.assertHasColumn("spam_numbers", "expiresAt")
        db.assertHasColumn("spam_numbers", "evidenceJson")
        db.assertHasColumn("spam_numbers", "evidenceExpiresAt")
        db.assertHasColumn("spam_prefixes", "evidenceJson")
        db.assertHasColumn("spam_prefixes", "evidenceExpiresAt")
        db.assertHasColumn("campaign_observations", "prefix")
        db.assertHasColumn("whitelist", "expiresAt")
        db.assertHasColumn("restore_journal", "phase")
        db.close()
    }

    @Test
    fun migrateFromVersion6ToCurrentPreservesEmergencyContacts() {
        LegacyDatabases.create(context, TEST_DB, version = 6)

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt("SELECT isEmergency FROM whitelist WHERE number = '+15550000003'", 1)
        db.assertHasColumn("hash_wildcard_rules", "pattern")
        db.close()
    }

    @Test
    fun migrateFromVersion7ToCurrentAddsHashRuleScheduleDefaults() {
        LegacyDatabases.create(context, TEST_DB, version = 7)

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt(
            "SELECT scheduleDays FROM hash_wildcard_rules WHERE pattern = '+1555######'",
            0,
        )
        db.assertSingleInt(
            "SELECT scheduleEndHour FROM hash_wildcard_rules WHERE pattern = '+1555######'",
            0,
        )
        db.close()
    }

    @Test
    fun migrateFromVersion8ToCurrentAddsRuleScheduleDefaults() {
        LegacyDatabases.create(context, TEST_DB, version = 8)

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt("SELECT scheduleDays FROM wildcard_rules WHERE pattern = '+1666*'", 0)
        db.assertSingleInt("SELECT scheduleEndHour FROM sms_keyword_rules WHERE keyword = 'refund'", 0)
        db.assertSingleInt(
            "SELECT scheduleStartHour FROM hash_wildcard_rules WHERE pattern = '+1666######'",
            9,
        )
        db.assertHasColumn("pending_blocked_call_logs", "nextAttemptAt")
        db.close()
    }

    @Test
    fun migrateFromVersion9ToCurrentAddsPendingBlockedCallLogQueue() {
        LegacyDatabases.create(context, TEST_DB, version = 9)

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_9_10,
                MIGRATION_10_11,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt("SELECT COUNT(*) FROM call_log WHERE number = '+17770000001'", 1)
        db.assertHasColumn("call_log", "logKey")
        db.assertSingleText("SELECT matchReason FROM call_log WHERE number = '+17770000001'", "database")
        db.assertSingleText("SELECT reasonCode FROM call_log WHERE number = '+17770000001'", "database")
        db.assertHasColumn("pending_blocked_call_logs", "idempotencyKey")
        db.assertHasColumn("pending_blocked_call_logs", "nextAttemptAt")
        db.assertSingleText("SELECT reasonCode FROM call_log WHERE number = '+17770000001'", "database")
        db.assertHasColumn("spam_numbers", "expiresAt")
        db.assertHasColumn("whitelist", "expiresAt")
        db.close()
    }

    @Test
    fun migrateFromVersion11CanonicalizesIdentityKeysAndMergesManualDecisions() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                """
                INSERT INTO spam_numbers
                    (number, type, reports, firstSeen, lastSeen, description, source, isUserBlocked, expiresAt)
                VALUES ('+12125551234', 'robocall', 8, '2026-01-01', '2026-01-02', 'feed', 'github', 0, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO spam_numbers
                    (number, type, reports, firstSeen, lastSeen, description, source, isUserBlocked, expiresAt)
                VALUES ('2125551234', 'spam', 1, '', '', 'manual', 'user', 1, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO whitelist (number, description, addedTimestamp, isEmergency, expiresAt)
                VALUES ('+14155550100', 'existing', 100, 0, NULL),
                       ('4155550100', 'doctor', 200, 1, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO call_log
                    (number, timestamp, type, wasBlocked, isCall, smsBody, matchReason, confidence, logKey)
                VALUES ('6505550100', 100, 'spam', 1, 1, NULL, 'manual', 100, 'phone-log'),
                       ('Bank-Alert', 200, 'sms_spam', 1, 0, NULL, 'content', 90, 'sender-log')
                """.trimIndent(),
            )
            close()
        }

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                migration11To12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )

        db.assertSingleInt("SELECT COUNT(*) FROM spam_numbers WHERE number = '+12125551234'", 1)
        db.assertSingleInt("SELECT isUserBlocked FROM spam_numbers WHERE number = '+12125551234'", 1)
        db.assertSingleText("SELECT source FROM spam_numbers WHERE number = '+12125551234'", "user")
        db.assertSingleInt("SELECT COUNT(*) FROM whitelist WHERE number = '+14155550100'", 1)
        db.assertSingleInt("SELECT isEmergency FROM whitelist WHERE number = '+14155550100'", 1)
        db.assertSingleText("SELECT number FROM call_log WHERE logKey = 'phone-log'", "+16505550100")
        db.assertSingleText("SELECT number FROM call_log WHERE logKey = 'sender-log'", "BANK-ALERT")
        db.assertSingleText("SELECT matchReason FROM call_log WHERE logKey = 'phone-log'", "manual")
        db.assertSingleText("SELECT reasonCode FROM call_log WHERE logKey = 'phone-log'", "unknown")
        db.assertHasColumn("call_log", "origid")
        db.assertHasColumn("pending_blocked_call_logs", "origid")
        db.assertHasColumn("call_log", "pipelineDiagnostic")
        db.assertHasColumn("pending_blocked_call_logs", "pipelineDiagnostic")
        db.close()
    }

    private fun SupportSQLiteDatabase.assertSingleInt(
        sql: String,
        expected: Int,
    ) {
        val cursor = query(sql)
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getInt(0))
        }
    }

    private fun SupportSQLiteDatabase.assertHasColumn(
        tableName: String,
        columnName: String,
    ) {
        val cursor = query("PRAGMA table_info(`$tableName`)")
        cursor.use {
            val nameIndex = it.getColumnIndexOrThrow("name")
            var found = false
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == columnName) {
                    found = true
                    break
                }
            }
            assertTrue("$tableName must include $columnName", found)
        }
    }

    private fun SupportSQLiteDatabase.assertSingleText(
        sql: String,
        expected: String,
    ) {
        val cursor = query(sql)
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "callshield-migration-test.db"
    }
}
