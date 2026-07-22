@file:Suppress("MagicNumber")

package com.sysadmindoc.callshield.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer

internal fun phoneIdentityMigration(canonicalizer: PhoneIdentityCanonicalizer): Migration =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateSpamNumbers(db, canonicalizer)
            migrateWhitelist(db, canonicalizer)
            canonicalizeLogTable(db, "call_log", "id", canonicalizer)
            canonicalizeLogTable(db, "pending_blocked_call_logs", "idempotencyKey", canonicalizer)
        }
    }

private data class SpamRow(
    val id: Long,
    val number: String,
    val type: String,
    val reports: Int,
    val firstSeen: String,
    val lastSeen: String,
    val description: String,
    val source: String,
    val isUserBlocked: Boolean,
    val expiresAt: Long?,
)

private data class WhitelistRow(
    val id: Long,
    val number: String,
    val description: String,
    val addedTimestamp: Long,
    val isEmergency: Boolean,
    val expiresAt: Long?,
)

private fun migrateSpamNumbers(
    db: SupportSQLiteDatabase,
    canonicalizer: PhoneIdentityCanonicalizer,
) {
    val rows =
        db
            .query(
                "SELECT id, number, type, reports, firstSeen, lastSeen, description, source, " +
                    "isUserBlocked, expiresAt FROM spam_numbers",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SpamRow(
                                id = cursor.getLong(0),
                                number = cursor.getString(1),
                                type = cursor.getString(2),
                                reports = cursor.getInt(3),
                                firstSeen = cursor.getString(4),
                                lastSeen = cursor.getString(5),
                                description = cursor.getString(6),
                                source = cursor.getString(7),
                                isUserBlocked = cursor.getInt(8) != 0,
                                expiresAt = cursor.getNullableLong(9),
                            ),
                        )
                    }
                }
            }
    rows
        .groupBy { row -> canonicalizer.canonicalizePhone(row.number).ifBlank { row.number } }
        .forEach { (canonicalNumber, matchingRows) ->
            val merged = mergeSpamRows(canonicalNumber, matchingRows)
            matchingRows.filterNot { it.id == merged.id }.forEach { row ->
                db.execSQL("DELETE FROM spam_numbers WHERE id = ?", arrayOf(row.id))
            }
            db.execSQL(
                """
                UPDATE spam_numbers
                SET number = ?, type = ?, reports = ?, firstSeen = ?, lastSeen = ?,
                    description = ?, source = ?, isUserBlocked = ?, expiresAt = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    merged.number,
                    merged.type,
                    merged.reports,
                    merged.firstSeen,
                    merged.lastSeen,
                    merged.description,
                    merged.source,
                    if (merged.isUserBlocked) 1 else 0,
                    merged.expiresAt,
                    merged.id,
                ),
            )
        }
}

private fun mergeSpamRows(
    canonicalNumber: String,
    rows: List<SpamRow>,
): SpamRow {
    val preferred =
        rows.maxWith(
            compareBy<SpamRow> { it.isUserBlocked }
                .thenBy { it.source == "user" }
                .thenBy { it.isUserBlocked && it.expiresAt == null }
                .thenBy { it.reports },
        )
    val survivor = rows.firstOrNull { it.number == canonicalNumber } ?: preferred
    val blockedRows = rows.filter(SpamRow::isUserBlocked)
    val mergedExpiry =
        when {
            blockedRows.isEmpty() -> null
            blockedRows.any { it.expiresAt == null } -> null
            else -> blockedRows.maxOf { requireNotNull(it.expiresAt) }
        }
    return survivor.copy(
        number = canonicalNumber,
        type = preferred.type,
        reports = rows.maxOf(SpamRow::reports),
        firstSeen =
            rows
                .map(SpamRow::firstSeen)
                .filter(String::isNotBlank)
                .minOrNull()
                .orEmpty(),
        lastSeen =
            rows
                .map(SpamRow::lastSeen)
                .filter(String::isNotBlank)
                .maxOrNull()
                .orEmpty(),
        description =
            preferred.description.ifBlank {
                rows.firstNotNullOfOrNull { it.description.ifBlank { null } }.orEmpty()
            },
        source = preferred.source,
        isUserBlocked = blockedRows.isNotEmpty(),
        expiresAt = mergedExpiry,
    )
}

private fun migrateWhitelist(
    db: SupportSQLiteDatabase,
    canonicalizer: PhoneIdentityCanonicalizer,
) {
    val rows =
        db
            .query(
                "SELECT id, number, description, addedTimestamp, isEmergency, expiresAt FROM whitelist",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WhitelistRow(
                                id = cursor.getLong(0),
                                number = cursor.getString(1),
                                description = cursor.getString(2),
                                addedTimestamp = cursor.getLong(3),
                                isEmergency = cursor.getInt(4) != 0,
                                expiresAt = cursor.getNullableLong(5),
                            ),
                        )
                    }
                }
            }
    rows
        .groupBy { row -> canonicalizer.canonicalizePhone(row.number).ifBlank { row.number } }
        .forEach { (canonicalNumber, matchingRows) ->
            val latest = matchingRows.maxBy(WhitelistRow::addedTimestamp)
            val survivor = matchingRows.firstOrNull { it.number == canonicalNumber } ?: latest
            val merged =
                survivor.copy(
                    number = canonicalNumber,
                    description = latest.description.ifBlank { survivor.description },
                    addedTimestamp = matchingRows.maxOf(WhitelistRow::addedTimestamp),
                    isEmergency = matchingRows.any { it.isEmergency && it.expiresAt == null },
                    expiresAt =
                        if (matchingRows.any { it.expiresAt == null }) {
                            null
                        } else {
                            matchingRows.maxOf { requireNotNull(it.expiresAt) }
                        },
                )
            matchingRows.filterNot { it.id == merged.id }.forEach { row ->
                db.execSQL("DELETE FROM whitelist WHERE id = ?", arrayOf(row.id))
            }
            db.execSQL(
                """
                UPDATE whitelist
                SET number = ?, description = ?, addedTimestamp = ?, isEmergency = ?, expiresAt = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    merged.number,
                    merged.description,
                    merged.addedTimestamp,
                    if (merged.isEmergency) 1 else 0,
                    merged.expiresAt,
                    merged.id,
                ),
            )
        }
}

private fun canonicalizeLogTable(
    db: SupportSQLiteDatabase,
    table: String,
    keyColumn: String,
    canonicalizer: PhoneIdentityCanonicalizer,
) {
    db.query("SELECT `$keyColumn`, number FROM `$table`").use { cursor ->
        while (cursor.moveToNext()) {
            val key = cursor.getString(0)
            val current = cursor.getString(1)
            val canonical = canonicalizer.canonicalizeIdentity(current)
            if (canonical.isNotBlank() && canonical != current) {
                db.execSQL(
                    "UPDATE `$table` SET number = ? WHERE `$keyColumn` = ?",
                    arrayOf(canonical, key),
                )
            }
        }
    }
}

private fun android.database.Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
