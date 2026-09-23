package com.sysadmindoc.callshield.data.local

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the migration chain that ships ([AppDatabase.builder]) from every
 * exported Room schema to [DB_VERSION] on the JVM, so `testDebugUnitTest`
 * fails when a migration doesn't produce what the entities declare.
 *
 * Each database is built from its schema JSON the way Room's
 * `MigrationTestHelper` builds one, gets one row per table, and is opened
 * through the production builder. After migrating, Room compares every
 * table, column and index with the entities and refuses to open on a
 * mismatch, which is how the index defect fixed on 2026-09-22 locked users
 * out. The instrumented `AppDatabaseMigrationTest` also covers hand-built
 * pre-export versions and migrated values, but no gate runs the instrumented
 * suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExportedSchemaMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun `every version from the first exported one up has a schema`() {
        assertEquals((FIRST_EXPORTED_VERSION..DB_VERSION).toList(), exportedSchemas().keys.sorted())
    }

    @Test
    fun `every exported schema migrates to the current version and passes Room's schema check`() {
        val failures =
            exportedSchemas().toSortedMap().mapNotNull { (version, schema) ->
                runCatching { migrateFrom(version, schema) }.exceptionOrNull()?.let { "v$version: $it" }
            }

        assertEquals("", failures.joinToString("\n"))
    }

    private fun migrateFrom(
        version: Int,
        schema: JsonObject,
    ) {
        context.deleteDatabase(TEST_DB)
        val tables = createFromSchema(version, schema)
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
            tables.forEach { table ->
                sqlite.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    cursor.moveToFirst()
                    check(cursor.getInt(0) == 1) { "$table lost its row" }
                }
            }
        } finally {
            database.close()
        }
    }

    /** Creates [version] from its schema with one row in each table, and returns the table names. */
    private fun createFromSchema(
        version: Int,
        schema: JsonObject,
    ): List<String> {
        val database = schema.getValue("database").jsonObject
        require("views" !in database) { "schema v$version has views, which this test doesn't create" }
        val entities = database.getValue("entities").jsonArray.map { it.jsonObject }
        val file = context.getDatabasePath(TEST_DB).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            entities.forEach { entity ->
                val table = entity.string("tableName")
                require("ftsVersion" !in entity) { "$table is an FTS table, which this test doesn't create" }
                db.execSQL(entity.string("createSql").replace(TABLE_NAME, table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.string("createSql").replace(TABLE_NAME, table))
                }
                db.insertOrThrow(table, null, seedRow(entity))
            }
            database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
        return entities.map { it.string("tableName") }
    }

    private fun seedRow(entity: JsonObject) =
        ContentValues().apply {
            entity.getValue("fields").jsonArray.map { it.jsonObject }.forEach { field ->
                val column = field.string("columnName")
                when (field.string("affinity")) {
                    "INTEGER" -> put(column, 1L)
                    "REAL" -> put(column, 1.0)
                    "BLOB" -> put(column, byteArrayOf(1))
                    else -> put(column, textSeed(column))
                }
            }
        }

    // A national-format number makes the phone-identity migration rewrite the row.
    private fun textSeed(column: String) =
        when {
            column.endsWith("Json") -> "[]"
            column == "number" || column == "pattern" -> "212-555-0123"
            else -> "seed"
        }

    private fun exportedSchemas(): Map<Int, JsonObject> {
        val directory = File(checkNotNull(System.getProperty("callshield.roomSchemas")) { "set by app/build.gradle.kts" })
        return directory
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .associate { file -> file.nameWithoutExtension.toInt() to Json.parseToJsonElement(file.readText()).jsonObject }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        const val TEST_DB = "exported-schema-migration-test.db"
        const val TABLE_NAME = "\${TABLE_NAME}"

        /** Schemas were exported from version 9; older databases are covered by the instrumented test. */
        const val FIRST_EXPORTED_VERSION = 9
    }
}
