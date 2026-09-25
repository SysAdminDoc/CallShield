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
        val nullsBefore = nullCounts()
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
            // A NULL means something, such as a message with no body, so a
            // migration keeps every one of them.
            nullsBefore.forEach { (column, count) ->
                val (table, name) = column.split('.')
                val after = sqlite.query("SELECT COUNT(*) FROM `$table` WHERE `$name` IS NULL").use { it.firstInt() }
                check(after == count) { "$column went from $count NULLs to $after" }
            }
        } finally {
            database.close()
        }
    }

    private fun rowCounts(): Map<String, Int> =
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.userTables().associateWith { table -> db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { it.firstInt() } }
        }

    /** How many NULLs each nullable column holds, keyed `table.column`. */
    private fun nullCounts(): Map<String, Int> =
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .userTables()
                .flatMap { table ->
                    val nullable =
                        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                                    val key = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) > 0
                                    if (!notNull && !key) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                                }
                            }
                        }
                    nullable.map { column ->
                        "$table.$column" to db.rawQuery("SELECT COUNT(*) FROM `$table` WHERE `$column` IS NULL", null).use { it.firstInt() }
                    }
                }.toMap()
        }

    private fun SQLiteDatabase.userTables(): List<String> =
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            .filterNot { it == "android_metadata" || it.startsWith("sqlite_") }

    private fun Cursor.firstInt(): Int {
        check(moveToFirst()) { "no row" }
        return getInt(0)
    }

    private companion object {
        const val TEST_DB = "legacy-schema-migration-test.db"
    }
}
