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
                    // TODO: Remove fallbackToDestructiveMigration() once proper Migration objects
                    // are defined for all version transitions (1→2, 2→3, etc.). We keep it for now
                    // because the app shipped without exportSchema=true, so historical schemas are
                    // unavailable and retroactive migrations cannot be written. From DB_VERSION 5
                    // onward, schemas are exported — write explicit addMigrations() for every
                    // future version bump and then remove this fallback.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
