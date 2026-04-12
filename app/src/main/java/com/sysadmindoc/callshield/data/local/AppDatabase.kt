package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sysadmindoc.callshield.data.model.*

/** Single source of truth for the Room database version. */
const val DB_VERSION = 5

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
                    // cannot be written. From DB_VERSION 5 (the current production version)
                    // onward, an explicit addMigrations() entry is REQUIRED for every future
                    // version bump. Without one Room will throw IllegalStateException at
                    // startup instead of silently wiping the user's blocklist, whitelist,
                    // wildcard rules, keyword rules, and blocked-call log.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
