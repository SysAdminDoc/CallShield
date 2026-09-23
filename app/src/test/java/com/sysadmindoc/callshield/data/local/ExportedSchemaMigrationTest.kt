package com.sysadmindoc.callshield.data.local

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    fun `a schema that has shipped never changes`() {
        // Room refuses to open a database whose stored identity hash differs from
        // the build's at the same version, so a column added to a shipped schema
        // without a version bump would lock out every install already on it.
        val hashes = exportedSchemas().mapValues { (_, schema) -> schema.getValue("database").jsonObject.string("identityHash") }
        SHIPPED_IDENTITY_HASHES.forEach { (version, hash) ->
            assertEquals("v$version changed after it shipped; bump DB_VERSION and add a migration", hash, hashes[version])
        }
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
            // Every table holds a filled row and a row with NULL in each nullable
            // column. A v11 database also holds the filled number twice (national and
            // E.164), which the identity migration merges, so two rows remain.
            tables.forEach { table ->
                check(sqlite.single("SELECT COUNT(*) FROM `$table`") == "2") { "$table doesn't hold its two rows" }
            }
            if (version < IDENTITY_MIGRATION_TARGET) {
                IDENTITY_TABLES.forEach { table ->
                    check(sqlite.single("SELECT COUNT(*) FROM $table WHERE number = '$CANONICAL_NUMBER'") == "1") {
                        "$table didn't merge the national form into $CANONICAL_NUMBER"
                    }
                }
            }
            if (version < REASON_CODE_VERSION) {
                check(sqlite.single("SELECT COUNT(*) FROM call_log WHERE reasonCode = '$SEEDED_MATCH_REASON'") == "2") {
                    "call_log reasonCode wasn't backfilled"
                }
            }
        } finally {
            database.close()
        }
    }

    /** Creates [version] from its schema with two rows in each table, and returns the table names. */
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
                if (version < IDENTITY_MIGRATION_TARGET && table in IDENTITY_TABLES) {
                    val twin =
                        seedRow(entity).apply {
                            put("id", 2L)
                            put("number", CANONICAL_NUMBER)
                        }
                    db.insertOrThrow(table, null, twin)
                }
                db.insertOrThrow(table, null, seedRow(entity, nullsWherePossible = true))
            }
            database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
        return entities.map { it.string("tableName") }
    }

    /**
     * One row for [entity]. The second row per table leaves every nullable column
     * NULL and uses different keys (id 3, other text) so unique indexes hold.
     */
    private fun seedRow(
        entity: JsonObject,
        nullsWherePossible: Boolean = false,
    ) = ContentValues().apply {
        entity.getValue("fields").jsonArray.map { it.jsonObject }.forEach { field ->
            val column = field.string("columnName")
            val nullable = field["notNull"]?.jsonPrimitive?.content != "true"
            when {
                nullsWherePossible && nullable -> putNull(column)
                field.string("affinity") == "INTEGER" -> put(column, if (nullsWherePossible) 3L else 1L)
                field.string("affinity") == "REAL" -> put(column, 1.0)
                field.string("affinity") == "BLOB" -> put(column, byteArrayOf(1))
                else -> put(column, textSeed(column, second = nullsWherePossible))
            }
        }
    }

    // A national-format number makes the phone-identity migration rewrite the row,
    // and a real match reason gives the reason-code backfill something to map.
    private fun textSeed(
        column: String,
        second: Boolean,
    ) = when {
        column.endsWith("Json") -> "[]"
        column == "number" || column == "pattern" -> if (second) "212-555-0199" else "212-555-0123"
        column == "matchReason" -> SEEDED_MATCH_REASON
        else -> if (second) "seed-2" else "seed"
    }

    private fun SupportSQLiteDatabase.single(sql: String): String? = query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

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

        /** MIGRATION_11_12 merges numbers stored in national and E.164 form. */
        const val IDENTITY_MIGRATION_TARGET = 12
        val IDENTITY_TABLES = setOf("spam_numbers", "whitelist")
        const val CANONICAL_NUMBER = "+12125550123"

        /** MIGRATION_14_15 adds reasonCode and backfills it from matchReason. */
        const val REASON_CODE_VERSION = 15
        const val SEEDED_MATCH_REASON = "heuristic"

        /**
         * Identity hash of every schema version that has shipped. Add the new
         * version here when it ships; before that it may still change.
         */
        val SHIPPED_IDENTITY_HASHES =
            mapOf(
                9 to "553a65f8c920bd3687274412d1a7bc01",
                10 to "6b0831cf184d6da2ba66bd8427010fe9",
                11 to "db8e0b5a37d63c2d55fb6342aa27f7e2",
                12 to "db8e0b5a37d63c2d55fb6342aa27f7e2",
                13 to "d21dee5bcedcdc604e1b7f62c611f793",
                14 to "5cc1f8197c4b90ddaf1e53fba3e64108",
                15 to "8771bab49e0e4076256126cf9f2973d1",
                16 to "44dd54cd1d75cfb6b77d16935925c3c7",
                17 to "54d29d872ca709df3394318cb541d850",
                18 to "a9364c0dbb096a12da7e79a74149f6f3",
            )
    }
}
