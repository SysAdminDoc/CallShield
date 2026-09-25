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
            // Every table holds a filled row and two rows with NULL in each nullable
            // column. A v11 database also holds the filled number twice (national and
            // E.164), which the identity migration merges, so three rows remain.
            tables.forEach { table ->
                check(sqlite.single("SELECT COUNT(*) FROM `${table.name}`") == "3") { "${table.name} doesn't hold its three rows" }
                // A NULL means something: a permanent allow or block has no expiresAt,
                // and a logged call without a key has no logKey. Both NULL rows keep them.
                val columns = sqlite.columns(table.name)
                table.nullRowKeys.forEach { key ->
                    table.nullableColumns.filter { it in columns }.forEach { column ->
                        val stillNull = sqlite.single("SELECT COUNT(*) FROM `${table.name}` WHERE `${table.keyColumn}` = ? AND `$column` IS NULL", key)
                        check(stillNull == "1") { "${table.name}.$column is no longer NULL in the row where ${table.keyColumn} = $key" }
                    }
                }
            }
            if (version < IDENTITY_MIGRATION_TARGET) {
                IDENTITY_TABLES.forEach { table ->
                    check(sqlite.single("SELECT COUNT(*) FROM $table WHERE number = '$CANONICAL_NUMBER'") == "1") {
                        "$table didn't merge the national form into $CANONICAL_NUMBER"
                    }
                }
                // The national twin has more reports, the E.164 twin the user's block.
                check(sqlite.single("SELECT reports FROM spam_numbers WHERE number = '$CANONICAL_NUMBER'") == "$NATIONAL_TWIN_REPORTS") {
                    "the merged spam_numbers row didn't keep the higher report count"
                }
                check(sqlite.single("SELECT isUserBlocked FROM spam_numbers WHERE number = '$CANONICAL_NUMBER'") == "1") {
                    "the merged spam_numbers row lost the user's block"
                }
            }
            if (version < REASON_CODE_VERSION) {
                check(sqlite.single("SELECT COUNT(*) FROM call_log WHERE reasonCode = '$SEEDED_MATCH_REASON'") == "3") {
                    "call_log reasonCode wasn't backfilled"
                }
            }
        } finally {
            database.close()
        }
    }

    /** A table as its source schema built it, with the keys of its two NULL rows. */
    private data class SeededTable(
        val name: String,
        val nullableColumns: List<String>,
        val keyColumn: String,
        val nullRowKeys: List<String>,
    )

    /** Creates [version] from its schema with three rows in each table (four in a v11 identity table). */
    private fun createFromSchema(
        version: Int,
        schema: JsonObject,
    ): List<SeededTable> {
        val database = schema.getValue("database").jsonObject
        require("views" !in database) { "schema v$version has views, which this test doesn't create" }
        val entities = database.getValue("entities").jsonArray.map { it.jsonObject }
        val file = context.getDatabasePath(TEST_DB).also { it.parentFile?.mkdirs() }
        val seeded =
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val tables =
                    entities.map { entity ->
                        val table = entity.string("tableName")
                        require("ftsVersion" !in entity) { "$table is an FTS table, which this test doesn't create" }
                        db.execSQL(entity.string("createSql").replace(TABLE_NAME, table))
                        entity["indices"]?.jsonArray?.forEach { index ->
                            db.execSQL(index.jsonObject.string("createSql").replace(TABLE_NAME, table))
                        }
                        val identityTwin = version < IDENTITY_MIGRATION_TARGET && table in IDENTITY_TABLES
                        db.insertOrThrow(
                            table,
                            null,
                            seedRow(entity).apply {
                                if (identityTwin && table == "spam_numbers") {
                                    put("reports", NATIONAL_TWIN_REPORTS)
                                    put("isUserBlocked", 0L)
                                }
                            },
                        )
                        if (identityTwin) {
                            val twin =
                                seedRow(entity).apply {
                                    put("id", 2L)
                                    put("number", CANONICAL_NUMBER)
                                    if (table == "spam_numbers") {
                                        put("reports", 2L)
                                        put("isUserBlocked", 1L)
                                    }
                                }
                            db.insertOrThrow(table, null, twin)
                        }
                        // Two rows with every nullable column NULL, so a unique index on a
                        // nullable column (call_log.logKey) holds two NULLs, the way a
                        // migration finds real rows.
                        val keyColumn =
                            entity
                                .getValue("primaryKey")
                                .jsonObject
                                .getValue("columnNames")
                                .jsonArray
                                .single()
                                .jsonPrimitive.content
                        val nullRowKeys =
                            listOf(NULL_ROW_A, NULL_ROW_B).map { variant ->
                                val row = seedRow(entity, variant)
                                db.insertOrThrow(table, null, row)
                                row.getAsString(keyColumn)
                            }
                        val nullable =
                            entity
                                .getValue("fields")
                                .jsonArray
                                .map { it.jsonObject }
                                .filter { it["notNull"]?.jsonPrimitive?.content != "true" }
                        SeededTable(table, nullable.map { it.string("columnName") }, keyColumn, nullRowKeys)
                    }
                database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.version = version
                tables
            }
        return seeded
    }

    /**
     * One row for [entity]. The NULL rows leave every nullable column NULL and
     * use their own keys (id 3 and 4, other text) so unique indexes hold.
     */
    private fun seedRow(
        entity: JsonObject,
        variant: Int = FILLED_ROW,
    ) = ContentValues().apply {
        entity.getValue("fields").jsonArray.map { it.jsonObject }.forEach { field ->
            val column = field.string("columnName")
            val nullable = field["notNull"]?.jsonPrimitive?.content != "true"
            when {
                variant != FILLED_ROW && nullable -> putNull(column)
                field.string("affinity") == "INTEGER" -> put(column, INTEGER_SEEDS[variant])
                field.string("affinity") == "REAL" -> put(column, 1.0)
                field.string("affinity") == "BLOB" -> put(column, byteArrayOf(1))
                else -> put(column, textSeed(column, variant))
            }
        }
    }

    // A national-format number makes the phone-identity migration rewrite the row,
    // and a real match reason gives the reason-code backfill something to map.
    private fun textSeed(
        column: String,
        variant: Int,
    ) = when {
        column.endsWith("Json") -> "[]"
        column == "number" || column == "pattern" -> NUMBER_SEEDS[variant]
        column == "matchReason" -> SEEDED_MATCH_REASON
        else -> TEXT_SEEDS[variant]
    }

    private fun SupportSQLiteDatabase.single(
        sql: String,
        vararg args: Any,
    ): String? = query(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
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

        /** MIGRATION_11_12 merges numbers stored in national and E.164 form. */
        const val IDENTITY_MIGRATION_TARGET = 12
        val IDENTITY_TABLES = setOf("spam_numbers", "whitelist")
        const val CANONICAL_NUMBER = "+12125550123"
        const val NATIONAL_TWIN_REPORTS = 9L

        const val FILLED_ROW = 0
        const val NULL_ROW_A = 1
        const val NULL_ROW_B = 2
        val INTEGER_SEEDS = listOf(1L, 3L, 4L)
        val NUMBER_SEEDS = listOf("212-555-0123", "212-555-0199", "212-555-0188")
        val TEXT_SEEDS = listOf("seed", "seed-2", "seed-3")

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
