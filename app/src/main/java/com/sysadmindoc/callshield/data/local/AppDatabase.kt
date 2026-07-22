package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.model.*

/** Single source of truth for the Room database version. */
const val DB_VERSION = 12
private const val DB_VERSION_9 = 9
private const val DB_VERSION_10 = 10
private const val DB_VERSION_11 = 11

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
    ],
    version = DB_VERSION,
    exportSchema = true,
)
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
