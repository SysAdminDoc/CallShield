package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.model.*

/** Single source of truth for the Room database version. */
const val DB_VERSION = 6

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

@Database(
    entities = [SpamNumber::class, SpamPrefix::class, BlockedCall::class, WildcardRule::class, WhitelistEntry::class, SmsKeywordRule::class],
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
                    .addMigrations(MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
