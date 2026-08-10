package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.model.*

/** Single source of truth for the Room database version. */
const val DB_VERSION = 18
private const val DB_VERSION_9 = 9
private const val DB_VERSION_10 = 10
private const val DB_VERSION_11 = 11
private const val DB_VERSION_12 = 12
private const val DB_VERSION_13 = 13
private const val DB_VERSION_14 = 14
private const val DB_VERSION_15 = 15
private const val DB_VERSION_16 = 16
private const val DB_VERSION_17 = 17
private const val DB_VERSION_18 = 18

/**
 * v5 → v6: Add `isEmergency INTEGER NOT NULL DEFAULT 0` to the whitelist
 * table so users can flag a subset of whitelist entries as emergency
 * contacts that always ring through regardless of quiet hours,
 * aggressive mode, blocklist, etc. Default 0 so existing whitelist rows
 * retain their current behavior.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE whitelist ADD COLUMN isEmergency INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v6 → v7: Add the `hash_wildcard_rules` table for Saracroche-style
 * length-locked `#` wildcard patterns (see data/HashWildcardMatcher.kt).
 *
 * These live in their own table (not reused from `wildcard_rules`)
 * because `#` patterns and `*`/regex patterns have genuinely different
 * semantics:
 *   - `#` is length-locked; `*` is variable-length.
 *   - `#` matches exactly one digit; `*` matches any substring.
 *   - `#` patterns expose a coverage count (10^N) that glob/regex
 *     patterns can't compute in general.
 *
 * Keeping them separate means the rule-edit UI can show each rule type
 * with its own semantics rather than a confusing "isHashPattern: Boolean"
 * flag on a shared row.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `hash_wildcard_rules` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `pattern` TEXT NOT NULL,
                    `description` TEXT NOT NULL DEFAULT '',
                    `enabled` INTEGER NOT NULL DEFAULT 1,
                    `addedTimestamp` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_hash_wildcard_rules_pattern` " +
                    "ON `hash_wildcard_rules`(`pattern`)",
            )
        }
    }

/**
 * v7 → v8: Add A7 per-rule schedule gating to `hash_wildcard_rules`.
 *
 * Three plain columns rather than an @Embedded TimeSchedule because the
 * sentinel is `scheduleDays == 0` → "no gating" (see data/TimeSchedule.kt),
 * which means a rule inserted without schedule columns (legacy row after
 * this migration) still behaves as "always active" without any special
 * handling. Default values ensure the ALTER is safe.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hash_wildcard_rules ADD COLUMN scheduleDays INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE hash_wildcard_rules ADD COLUMN scheduleStartHour INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE hash_wildcard_rules ADD COLUMN scheduleEndHour INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v8 → v9: Extend the A7 schedule gating to the remaining rule tables —
 * `wildcard_rules` (glob/regex) and `sms_keyword_rules`. Same three
 * columns, same `DEFAULT 0` sentinel → "no gating, always active".
 *
 * Additive ALTER statements keep existing rows untouched; users who
 * haven't set a schedule experience identical pre-v9 behaviour.
 */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleDays INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleStartHour INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleEndHour INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleDays INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleStartHour INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleEndHour INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v9 -> v10: Add a durable, idempotent pending-log queue for blocked calls.
 *
 * CallScreeningService still answers Android immediately, but now inserts a
 * tiny pending row first. A follow-up worker consumes that row into `call_log`.
 * The nullable unique `logKey` on final call-log rows prevents duplicate log
 * entries if a retry races with an already-consumed pending row.
 */
val MIGRATION_9_10 =
    object : Migration(DB_VERSION_9, DB_VERSION_10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE call_log ADD COLUMN logKey TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_call_log_logKey` ON `call_log`(`logKey`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_blocked_call_logs` (
                    `idempotencyKey` TEXT NOT NULL,
                    `number` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `isCall` INTEGER NOT NULL,
                    `smsBody` TEXT,
                    `matchReason` TEXT NOT NULL,
                    `confidence` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `attempts` INTEGER NOT NULL,
                    `nextAttemptAt` INTEGER NOT NULL,
                    PRIMARY KEY(`idempotencyKey`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_blocked_call_logs_createdAt` " +
                    "ON `pending_blocked_call_logs`(`createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_blocked_call_logs_nextAttemptAt` " +
                    "ON `pending_blocked_call_logs`(`nextAttemptAt`)",
            )
        }
    }

/**
 * v10 -> v11: Add nullable expiry timestamps for temporary user decisions.
 *
 * `spam_numbers.expiresAt` scopes temporary block rows and temporary user-block
 * flags on synced rows. `whitelist.expiresAt` scopes temporary allow rows.
 * NULL keeps existing permanent block/allow semantics.
 */
val MIGRATION_10_11 =
    object : Migration(DB_VERSION_10, DB_VERSION_11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE spam_numbers ADD COLUMN expiresAt INTEGER")
            db.execSQL("ALTER TABLE whitelist ADD COLUMN expiresAt INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_spam_numbers_expiresAt` " +
                    "ON `spam_numbers`(`expiresAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_whitelist_expiresAt` " +
                    "ON `whitelist`(`expiresAt`)",
            )
        }
    }

/** v12 -> v13: Add the singleton two-phase backup-restore journal. */
val MIGRATION_12_13 =
    object : Migration(DB_VERSION_12, DB_VERSION_13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `restore_journal` (
                    `journalId` INTEGER NOT NULL,
                    `phase` TEXT NOT NULL,
                    `beforeSettingsJson` TEXT NOT NULL,
                    `desiredSettingsJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`journalId`)
                )
                """.trimIndent(),
            )
        }
    }

/** v13 -> v14: retain source evidence and independent feed freshness. */
val MIGRATION_13_14 =
    object : Migration(DB_VERSION_13, DB_VERSION_14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE spam_numbers ADD COLUMN evidenceJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE spam_numbers ADD COLUMN evidenceExpiresAt INTEGER")
            db.execSQL("ALTER TABLE spam_prefixes ADD COLUMN evidenceJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE spam_prefixes ADD COLUMN evidenceExpiresAt INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_spam_numbers_evidenceExpiresAt` " +
                    "ON `spam_numbers`(`evidenceExpiresAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_spam_prefixes_evidenceExpiresAt` " +
                    "ON `spam_prefixes`(`evidenceExpiresAt`)",
            )
        }
    }

/** v14 -> v15: retain the responsible user-rule row on screening log entries. */
val MIGRATION_14_15 =
    object : Migration(DB_VERSION_14, DB_VERSION_15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE call_log ADD COLUMN ruleId INTEGER")
            db.execSQL("ALTER TABLE pending_blocked_call_logs ADD COLUMN ruleId INTEGER")
            db.execSQL("ALTER TABLE call_log ADD COLUMN reasonCode TEXT NOT NULL DEFAULT 'unknown'")
            db.execSQL("ALTER TABLE pending_blocked_call_logs ADD COLUMN reasonCode TEXT NOT NULL DEFAULT 'unknown'")
            db.execSQL("UPDATE call_log SET reasonCode = ${reasonCodeSql("matchReason")}")
            db.execSQL("UPDATE pending_blocked_call_logs SET reasonCode = ${reasonCodeSql("matchReason")}")
        }
    }

/** v15 -> v16: retain bounded local neighbor-number campaign observations. */
val MIGRATION_15_16 =
    object : Migration(DB_VERSION_15, DB_VERSION_16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `campaign_observations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `number` TEXT NOT NULL,
                    `prefix` TEXT NOT NULL,
                    `observedAt` INTEGER NOT NULL,
                    `sourceIds` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_campaign_observations_prefix_observedAt` " +
                    "ON `campaign_observations`(`prefix`, `observedAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_campaign_observations_observedAt` " +
                    "ON `campaign_observations`(`observedAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_campaign_observations_number_observedAt` " +
                    "ON `campaign_observations`(`number`, `observedAt`)",
            )
        }
    }

/** v16 -> v17: retain only the PASSporT origid correlation UUID on call logs. */
val MIGRATION_16_17 =
    object : Migration(DB_VERSION_16, DB_VERSION_17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE call_log ADD COLUMN origid TEXT")
            db.execSQL("ALTER TABLE pending_blocked_call_logs ADD COLUMN origid TEXT")
        }
    }

/** v17 -> v18: retain privacy-safe checker cutoff/error diagnostics. */
val MIGRATION_17_18 =
    object : Migration(DB_VERSION_17, DB_VERSION_18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE call_log ADD COLUMN pipelineDiagnostic TEXT")
            db.execSQL("ALTER TABLE pending_blocked_call_logs ADD COLUMN pipelineDiagnostic TEXT")
        }
    }

private fun reasonCodeSql(column: String): String =
    """
    CASE
        WHEN lower(trim($column)) LIKE 'rcs_%' THEN 'rcs_filter'
        WHEN lower(trim($column)) LIKE 'category_policy:%' THEN 'category_policy'
        WHEN lower(trim($column)) LIKE 'database%' THEN 'database'
        WHEN lower(trim($column)) LIKE 'db_prefix%' THEN 'db_prefix_expansion'
        WHEN lower(trim($column)) LIKE 'hash_wildcard%' THEN 'hash_wildcard'
        WHEN lower(trim($column)) LIKE 'wildcard%' THEN 'wildcard'
        WHEN lower(trim($column)) LIKE 'prefix%' THEN 'prefix'
        WHEN lower(trim($column)) LIKE 'heuristic%' THEN 'heuristic'
        WHEN lower(trim($column)) LIKE 'campaign_burst%' THEN 'campaign_burst'
        WHEN lower(trim($column)) LIKE 'hot_campaign%' THEN 'campaign_burst'
        WHEN lower(trim($column)) LIKE 'ml_scorer%' THEN 'ml_scorer'
        WHEN lower(trim($column)) LIKE 'known_spam_domain%' THEN 'spam_domain'
        WHEN lower(trim($column)) LIKE 'local_spam_domain%' THEN 'spam_domain'
        WHEN lower(trim($column)) LIKE 'spam_keywords%' THEN 'keyword'
        WHEN lower(trim($column)) LIKE 'sms_content%' THEN 'sms_content'
        WHEN lower(trim($column)) LIKE 'sms_burst%' THEN 'sms_burst'
        WHEN lower(trim($column)) IN (
            'emergency_floor', 'otp_floor', 'emergency_contact', 'manual_whitelist',
            'contact_whitelist', 'contacts_only', 'stir_shaken_trusted',
            'stir_shaken_failed', 'temporary_allow', 'temporary_block',
            'system_block_list', 'user_blocklist', 'hidden_number',
            'prefix', 'recently_dialed', 'answered_caller', 'emergency_callback',
            'repeated_urgent', 'caller_name_trust', 'caller_name', 'region_block',
            'campaign_recorder', 'time_block', 'frequency', 'push_alert',
            'sms_context', 'keyword', 'sms_content', 'rcs_filter', 'hot_list',
            'spam_domain', 'category_policy', 'pipeline_diagnostic', 'unknown'
        ) THEN lower(trim($column))
        ELSE 'unknown'
    END
    """.trimIndent()

@Database(
    entities = [
        SpamNumber::class,
        SpamPrefix::class,
        BlockedCall::class,
        WildcardRule::class,
        WhitelistEntry::class,
        SmsKeywordRule::class,
        HashWildcardRule::class,
        PendingBlockedCallLog::class,
        RestoreJournal::class,
        CampaignObservation::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
)
@TypeConverters(BlockReasonCodeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spamDao(): SpamDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "callshield.db",
                    )
                    // Destructive migration is restricted to legacy schema versions (1–4)
                    // whose schemas were never exported, so retroactive Migration objects
                    // cannot be written. From DB_VERSION 5 onward EVERY version bump
                    // REQUIRES an explicit Migration — Room will throw
                    // IllegalStateException at startup if one is missing instead of
                    // silently wiping user data.
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4)
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        phoneIdentityMigration(
                            PhoneIdentityCanonicalizer.fromContext(context.applicationContext),
                        ),
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                    ).build()
                    .also { instance = it }
            }

        /** SQLite corruption messages that a rebuild can recover from. */
        private val CORRUPTION_MESSAGES =
            listOf(
                "database disk image is malformed",
                "file is not a database",
                "file is encrypted or is not a database",
            )

        /**
         * True when [t] (or any cause in its chain) indicates on-disk SQLite
         * corruption rather than a transient/logic error. Pure type/string
         * matching so it is unit-testable off-device without touching the
         * Android SQLite stack. The cause chain is walked with a depth bound
         * so a self-referential cause can't spin.
         */
        fun isCorruptionException(t: Throwable?): Boolean {
            var cur = t
            var depth = 0
            while (cur != null && depth < 12) {
                if (cur.javaClass.name.endsWith("SQLiteDatabaseCorruptException")) return true
                val msg = cur.message?.lowercase().orEmpty()
                if (CORRUPTION_MESSAGES.any { msg.contains(it) }) return true
                cur = cur.cause
                depth++
            }
            return false
        }

        /**
         * Delete a corrupt on-disk database and drop the cached instance so
         * the next [getInstance] rebuilds a clean schema. Spam numbers are
         * re-syncable from GitHub/bundled data, so wiping the local copy is an
         * acceptable recovery from an otherwise-unrecoverable file — far
         * better than the DAO throwing forever and the screener failing open.
         * Returns true if a database file was deleted.
         */
        fun recoverFromCorruption(context: Context): Boolean =
            synchronized(this) {
                try {
                    instance?.close()
                } catch (_: Exception) {
                }
                instance = null
                context.applicationContext.deleteDatabase("callshield.db")
            }
    }
}
