package com.sysadmindoc.callshield.data.local

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades the databases versions 5 to 9 left behind, built by hand in
 * [LegacyDatabases] because Room's schema export started at version 9, through
 * the migration chain that ships ([AppDatabase.builder]). Room's post-migration
 * check has to pass and no table may lose a row. `ExportedSchemaMigrationTest`
 * covers the exported versions; the instrumented `AppDatabaseMigrationTest`
 * checks migrated values on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LegacySchemaMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun `databases from before schema export migrate to the current version`() {
        val failures =
            LegacyDatabases.VERSIONS.mapNotNull { version ->
                runCatching { migrateFrom(version) }.exceptionOrNull()?.let { "v$version: $it" }
            }

        assertEquals("", failures.joinToString("\n"))
    }

    private fun migrateFrom(version: Int) {
        LegacyDatabases.create(context, TEST_DB, version)
        val before = rowCounts()
        // Robolectric's native SQLite can't open WAL on Windows (SQLITE_CANTOPEN);
        // the journal mode has no bearing on migrations or Room's schema check.
        val database =
            AppDatabase
                .builder(context, TEST_DB)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            check(sqlite.version == DB_VERSION) { "ended at version ${sqlite.version}" }
            before.forEach { (table, count) ->
                val after = sqlite.query("SELECT COUNT(*) FROM `$table`").use { it.firstInt() }
                check(after == count) { "$table went from $count rows to $after" }
            }
        } finally {
            database.close()
        }
    }

    private fun rowCounts(): Map<String, Int> =
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables =
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            tables
                .filterNot { it == "android_metadata" || it.startsWith("sqlite_") }
                .associateWith { table -> db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { it.firstInt() } }
        }

    private fun Cursor.firstInt(): Int {
        check(moveToFirst()) { "no row" }
        return getInt(0)
    }

    private companion object {
        const val TEST_DB = "legacy-schema-migration-test.db"
    }
}
