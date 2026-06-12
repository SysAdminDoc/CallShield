package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        createLegacyDatabase(
            version = 5,
            createSchema = { createVersion5Schema() },
            seed = { seedVersion5Data() },
        )

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )

        db.assertSingleInt("SELECT COUNT(*) FROM spam_numbers", 1)
        db.assertSingleInt("SELECT isEmergency FROM whitelist WHERE number = '+15550000002'", 0)
        db.assertSingleInt("SELECT scheduleDays FROM wildcard_rules WHERE pattern = '+1555*'", 0)
        db.assertSingleInt("SELECT scheduleStartHour FROM sms_keyword_rules WHERE keyword = 'prize'", 0)
        db.assertHasColumn("hash_wildcard_rules", "scheduleEndHour")
        db.close()
    }

    @Test
    fun migrateFromVersion6ToCurrentPreservesEmergencyContacts() {
        createLegacyDatabase(
            version = 6,
            createSchema = { createVersion6Schema() },
            seed = { seedVersion6Data() },
        )

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )

        db.assertSingleInt("SELECT isEmergency FROM whitelist WHERE number = '+15550000003'", 1)
        db.assertHasColumn("hash_wildcard_rules", "pattern")
        db.close()
    }

    @Test
    fun migrateFromVersion7ToCurrentAddsHashRuleScheduleDefaults() {
        createLegacyDatabase(
            version = 7,
            createSchema = { createVersion7Schema() },
            seed = { seedVersion7Data() },
        )

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_7_8,
                MIGRATION_8_9,
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
        createLegacyDatabase(
            version = 8,
            createSchema = { createVersion8Schema() },
            seed = { seedVersion8Data() },
        )

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                DB_VERSION,
                true,
                MIGRATION_8_9,
            )

        db.assertSingleInt("SELECT scheduleDays FROM wildcard_rules WHERE pattern = '+1666*'", 0)
        db.assertSingleInt("SELECT scheduleEndHour FROM sms_keyword_rules WHERE keyword = 'refund'", 0)
        db.assertSingleInt(
            "SELECT scheduleStartHour FROM hash_wildcard_rules WHERE pattern = '+1666######'",
            9,
        )
        db.close()
    }

    private fun createLegacyDatabase(
        version: Int,
        createSchema: SupportSQLiteDatabase.() -> Unit,
        seed: SupportSQLiteDatabase.() -> Unit,
    ) {
        context.deleteDatabase(TEST_DB)

        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.createSchema()
                    db.seed()
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = error("Unexpected upgrade from $oldVersion to $newVersion")
            }

        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(TEST_DB)
                .callback(callback)
                .build()

        val openHelper =
            FrameworkSQLiteOpenHelperFactory()
                .create(configuration)

        openHelper.writableDatabase.close()
        openHelper.close()
    }

    private fun SupportSQLiteDatabase.createVersion5Schema() {
        createCommonTables()
        createWildcardRulesTable(includeSchedule = false)
        createWhitelistTable(includeEmergency = false)
        createSmsKeywordRulesTable(includeSchedule = false)
    }

    private fun SupportSQLiteDatabase.createVersion6Schema() {
        createCommonTables()
        createWildcardRulesTable(includeSchedule = false)
        createWhitelistTable(includeEmergency = true)
        createSmsKeywordRulesTable(includeSchedule = false)
    }

    private fun SupportSQLiteDatabase.createVersion7Schema() {
        createVersion6Schema()
        createHashWildcardRulesTable(includeSchedule = false)
    }

    private fun SupportSQLiteDatabase.createVersion8Schema() {
        createVersion6Schema()
        createHashWildcardRulesTable(includeSchedule = true)
    }

    private fun SupportSQLiteDatabase.createCommonTables() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spam_numbers` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `number` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `reports` INTEGER NOT NULL,
                `firstSeen` TEXT NOT NULL,
                `lastSeen` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `isUserBlocked` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spam_numbers_number` ON `spam_numbers` (`number`)")

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spam_prefixes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `prefix` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `description` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spam_prefixes_prefix` ON `spam_prefixes` (`prefix`)")

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `call_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `number` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `wasBlocked` INTEGER NOT NULL,
                `isCall` INTEGER NOT NULL,
                `smsBody` TEXT,
                `matchReason` TEXT NOT NULL,
                `confidence` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS `index_call_log_number` ON `call_log` (`number`)")
        execSQL("CREATE INDEX IF NOT EXISTS `index_call_log_timestamp` ON `call_log` (`timestamp`)")
    }

    private fun SupportSQLiteDatabase.createWildcardRulesTable(includeSchedule: Boolean) {
        val scheduleColumns =
            if (includeSchedule) {
                ", `scheduleDays` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleStartHour` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleEndHour` INTEGER NOT NULL DEFAULT 0"
            } else {
                ""
            }

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wildcard_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `pattern` TEXT NOT NULL,
                `isRegex` INTEGER NOT NULL,
                `description` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL$scheduleColumns
            )
            """.trimIndent(),
        )
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wildcard_rules_pattern` ON `wildcard_rules` (`pattern`)")
    }

    private fun SupportSQLiteDatabase.createWhitelistTable(includeEmergency: Boolean) {
        val emergencyColumn =
            if (includeEmergency) {
                ", `isEmergency` INTEGER NOT NULL DEFAULT 0"
            } else {
                ""
            }

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `whitelist` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `number` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `addedTimestamp` INTEGER NOT NULL$emergencyColumn
            )
            """.trimIndent(),
        )
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_whitelist_number` ON `whitelist` (`number`)")
    }

    private fun SupportSQLiteDatabase.createSmsKeywordRulesTable(includeSchedule: Boolean) {
        val scheduleColumns =
            if (includeSchedule) {
                ", `scheduleDays` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleStartHour` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleEndHour` INTEGER NOT NULL DEFAULT 0"
            } else {
                ""
            }

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sms_keyword_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `keyword` TEXT NOT NULL,
                `caseSensitive` INTEGER NOT NULL,
                `description` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL$scheduleColumns
            )
            """.trimIndent(),
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_keyword_rules_keyword` " +
                "ON `sms_keyword_rules` (`keyword`)",
        )
    }

    private fun SupportSQLiteDatabase.createHashWildcardRulesTable(includeSchedule: Boolean) {
        val scheduleColumns =
            if (includeSchedule) {
                ", `scheduleDays` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleStartHour` INTEGER NOT NULL DEFAULT 0, " +
                    "`scheduleEndHour` INTEGER NOT NULL DEFAULT 0"
            } else {
                ""
            }

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hash_wildcard_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `pattern` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `addedTimestamp` INTEGER NOT NULL DEFAULT 0$scheduleColumns
            )
            """.trimIndent(),
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_hash_wildcard_rules_pattern` " +
                "ON `hash_wildcard_rules` (`pattern`)",
        )
    }

    private fun SupportSQLiteDatabase.seedVersion5Data() {
        execSQL(
            """
            INSERT INTO spam_numbers
                (number, type, reports, firstSeen, lastSeen, description, source, isUserBlocked)
            VALUES ('+15550000001', 'robocall', 3, '2026-01-01', '2026-01-02', 'seed', 'test', 1)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO whitelist (number, description, addedTimestamp)
            VALUES ('+15550000002', 'legacy allow', 12345)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO wildcard_rules (pattern, isRegex, description, enabled)
            VALUES ('+1555*', 0, 'legacy range', 1)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO sms_keyword_rules (keyword, caseSensitive, description, enabled)
            VALUES ('prize', 0, 'legacy sms', 1)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion6Data() {
        execSQL(
            """
            INSERT INTO whitelist (number, description, addedTimestamp, isEmergency)
            VALUES ('+15550000003', 'doctor', 23456, 1)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion7Data() {
        execSQL(
            """
            INSERT INTO hash_wildcard_rules (pattern, description, enabled, addedTimestamp)
            VALUES ('+1555######', 'legacy hash', 1, 34567)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion8Data() {
        execSQL(
            """
            INSERT INTO wildcard_rules (pattern, isRegex, description, enabled)
            VALUES ('+1666*', 0, 'legacy wildcard', 1)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO sms_keyword_rules (keyword, caseSensitive, description, enabled)
            VALUES ('refund', 0, 'legacy sms', 1)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO hash_wildcard_rules
                (pattern, description, enabled, addedTimestamp, scheduleDays, scheduleStartHour, scheduleEndHour)
            VALUES ('+1666######', 'scheduled hash', 1, 45678, 62, 9, 17)
            """.trimIndent(),
        )
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

    private companion object {
        const val TEST_DB = "callshield-migration-test.db"
    }
}
