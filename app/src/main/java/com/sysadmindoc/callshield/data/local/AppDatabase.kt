package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.model.*

/** Single source of truth for the Room database version. */
const val DB_VERSION = 9

/**
 * v5 → v6: Add `isEmergency INTEGER NOT NULL DEFAULT 0` to the whitelist
 * table so users can flag a subset of whitelist entries as emergency
 * contacts that always ring through regardless of quiet hours,
 * aggressive mode, blocklist, etc. Default 0 so existing whitelist rows
 * retain their current behavior.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
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
val MIGRATION_6_7 = object : Migration(6, 7) {
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
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_hash_wildcard_rules_pattern` " +
                "ON `hash_wildcard_rules`(`pattern`)"
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
val MIGRATION_7_8 = object : Migration(7, 8) {
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
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleStartHour INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE wildcard_rules ADD COLUMN scheduleEndHour INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleStartHour INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sms_keyword_rules ADD COLUMN scheduleEndHour INTEGER NOT NULL DEFAULT 0")
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
    ],
    version = DB_VERSION,
    // exportSchema requires Room Gradle plugin (id 'androidx.room') — enable when adding proper migrations
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spamDao(): SpamDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "callshield.db"
                )
                    // Destructive migration is restricted to legacy schema versions (1–4)
                    // whose schemas were never exported, so retroactive Migration objects
                    // cannot be written. From DB_VERSION 5 onward EVERY version bump
                    // REQUIRES an explicit Migration — Room will throw
                    // IllegalStateException at startup if one is missing instead of
                    // silently wiping user data.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
