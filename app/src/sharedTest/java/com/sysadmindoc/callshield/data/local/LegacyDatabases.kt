package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Databases as versions 5 to 9 left them. Room's schema export started at
 * version 9, so these are built by hand. Shared by the instrumented
 * `AppDatabaseMigrationTest` and the JVM `LegacySchemaMigrationTest`.
 */
internal object LegacyDatabases {
    val VERSIONS = 5..9

    /** Creates [name] at [version] with that version's schema and seed rows. */
    fun create(
        context: Context,
        name: String,
        version: Int,
    ) {
        require(version in VERSIONS) { "no hand-built schema for v$version" }
        context.deleteDatabase(name)

        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.createSchema(version)
                    db.seed(version)
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
                .name(name)
                .callback(callback)
                .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        openHelper.writableDatabase.close()
        openHelper.close()
    }

    private fun SupportSQLiteDatabase.createSchema(version: Int) {
        when (version) {
            5 -> {
                createCommonTables()
                createWildcardRulesTable(includeSchedule = false)
                createWhitelistTable(includeEmergency = false)
                createSmsKeywordRulesTable(includeSchedule = false)
            }

            6 -> {
                createVersion6Tables()
            }

            7 -> {
                createVersion6Tables()
                createHashWildcardRulesTable(includeSchedule = false)
            }

            8 -> {
                createVersion6Tables()
                createHashWildcardRulesTable(includeSchedule = true)
            }

            else -> {
                createCommonTables()
                createWildcardRulesTable(includeSchedule = true)
                createWhitelistTable(includeEmergency = true)
                createSmsKeywordRulesTable(includeSchedule = true)
                createHashWildcardRulesTable(includeSchedule = true)
            }
        }
    }

    private fun SupportSQLiteDatabase.seed(version: Int) {
        seedCommonTables()
        when (version) {
            5 -> seedVersion5()
            6 -> seedVersion6()
            7 -> seedVersion7()
            8 -> seedVersion8()
            else -> seedVersion9()
        }
    }

    private fun SupportSQLiteDatabase.createVersion6Tables() {
        createCommonTables()
        createWildcardRulesTable(includeSchedule = false)
        createWhitelistTable(includeEmergency = true)
        createSmsKeywordRulesTable(includeSchedule = false)
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
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wildcard_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `pattern` TEXT NOT NULL,
                `isRegex` INTEGER NOT NULL,
                `description` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL${scheduleColumns(includeSchedule)}
            )
            """.trimIndent(),
        )
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wildcard_rules_pattern` ON `wildcard_rules` (`pattern`)")
    }

    private fun SupportSQLiteDatabase.createWhitelistTable(includeEmergency: Boolean) {
        val emergencyColumn = if (includeEmergency) ", `isEmergency` INTEGER NOT NULL DEFAULT 0" else ""
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
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sms_keyword_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `keyword` TEXT NOT NULL,
                `caseSensitive` INTEGER NOT NULL,
                `description` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL${scheduleColumns(includeSchedule)}
            )
            """.trimIndent(),
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_keyword_rules_keyword` " +
                "ON `sms_keyword_rules` (`keyword`)",
        )
    }

    private fun SupportSQLiteDatabase.createHashWildcardRulesTable(includeSchedule: Boolean) {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hash_wildcard_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `pattern` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `addedTimestamp` INTEGER NOT NULL DEFAULT 0${scheduleColumns(includeSchedule)}
            )
            """.trimIndent(),
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_hash_wildcard_rules_pattern` " +
                "ON `hash_wildcard_rules` (`pattern`)",
        )
    }

    private fun scheduleColumns(includeSchedule: Boolean) =
        if (includeSchedule) {
            ", `scheduleDays` INTEGER NOT NULL DEFAULT 0, " +
                "`scheduleStartHour` INTEGER NOT NULL DEFAULT 0, " +
                "`scheduleEndHour` INTEGER NOT NULL DEFAULT 0"
        } else {
            ""
        }

    /**
     * Every version has spam_prefixes and call_log, so each carries a prefix and
     * a logged message with no body: the migrations rebuild both tables.
     */
    private fun SupportSQLiteDatabase.seedCommonTables() {
        execSQL("INSERT INTO spam_prefixes (prefix, type, description) VALUES ('+1900', 'premium', 'legacy prefix')")
        execSQL(
            """
            INSERT INTO call_log
                (number, timestamp, type, wasBlocked, isCall, smsBody, matchReason, confidence)
            VALUES ('+17770000002', 8888, 'sms_spam', 1, 0, NULL, 'sms_content', 80)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion5() {
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

    private fun SupportSQLiteDatabase.seedVersion6() {
        execSQL(
            """
            INSERT INTO whitelist (number, description, addedTimestamp, isEmergency)
            VALUES ('+15550000003', 'doctor', 23456, 1)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion7() {
        execSQL(
            """
            INSERT INTO hash_wildcard_rules (pattern, description, enabled, addedTimestamp)
            VALUES ('+1555######', 'legacy hash', 1, 34567)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion8() {
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

    private fun SupportSQLiteDatabase.seedVersion9() {
        execSQL(
            """
            INSERT INTO call_log
                (number, timestamp, type, wasBlocked, isCall, smsBody, matchReason, confidence)
            VALUES ('+17770000001', 7777, 'unknown', 1, 1, NULL, 'database', 100)
            """.trimIndent(),
        )
    }
}
