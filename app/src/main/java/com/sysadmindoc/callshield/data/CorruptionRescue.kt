package com.sysadmindoc.callshield.data

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupKeyword
import com.sysadmindoc.callshield.data.BackupRestore.BackupNumber
import com.sysadmindoc.callshield.data.BackupRestore.BackupRangeRule
import com.sysadmindoc.callshield.data.BackupRestore.BackupSection
import com.sysadmindoc.callshield.data.BackupRestore.BackupWhitelist
import com.sysadmindoc.callshield.data.BackupRestore.BackupWildcard
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.service.HotListSyncWorker
import com.sysadmindoc.callshield.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the user's own blocks, allow list and rules through the loss of a
 * damaged database. SQLite deletes the file as soon as a query finds a bad
 * page and carries on with an empty one. [save] runs just before that (see
 * [com.sysadmindoc.callshield.data.local.RescuingOpenHelperFactory]) and
 * writes whatever it can still read, in the portable backup format, under
 * noBackupFilesDir. [afterRebuild] puts those rows into the empty database
 * and refills the spam data, and the next start does it if the process ends
 * first.
 */
@Suppress("TooGenericExceptionCaught")
internal object CorruptionRescue {
    private const val TAG = "CorruptionRescue"
    private const val FILE_NAME = "corruption-rescue.json"

    val sections: Set<BackupSection> =
        setOf(
            BackupSection.BLOCKED_NUMBERS,
            BackupSection.WHITELIST,
            BackupSection.WILDCARD_RULES,
            BackupSection.KEYWORD_RULES,
            BackupSection.RANGE_RULES,
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isPending(context: Context): Boolean = file(context).exists()

    /** Saves what [db] still holds of the user's rows, beside any an earlier rescue saved. */
    fun save(
        context: Context,
        db: SupportSQLiteDatabase,
    ) {
        val rescued = read(db)
        val earlier = readSaved(context)
        write(context, earlier?.let { merge(it, rescued) } ?: rescued)
        Log.w(TAG, "Saved ${rescued.rowCount()} of the user's own rows from a damaged database")
    }

    /** Once SQLite has replaced a damaged database, puts the user's rows back and refills the spam data. */
    fun afterRebuild(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                importPending(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't put the saved rows back yet; the next start will", e)
            }
            SyncWorker.syncNow(appContext)
            // The rebuilt database has no trending rows, and the device has
            // applied a hot list before, so the bundled copy won't stand in.
            HotListSyncWorker.schedule(appContext)
            HotListSyncWorker.syncNow(appContext)
        }
    }

    suspend fun importPending(context: Context): Boolean =
        isPending(context) &&
            importPending(context, AppDatabase.getInstance(context).spamDao(), SpamRepository.getInstance(context))

    internal suspend fun importPending(
        context: Context,
        dao: SpamDao,
        repo: SpamRepository,
    ): Boolean {
        if (!isPending(context)) return false
        val backup = readSaved(context)
        val restored =
            backup == null ||
                backup.rowCount() == 0 ||
                BackupRestore.restoreOwnRows(context, backup, dao, repo, sections).success
        if (restored) {
            file(context).delete()
        } else {
            Log.w(TAG, "Couldn't put the saved rows back; trying again at the next start")
        }
        return true
    }

    /** Each table on its own, so a damaged one costs only its own rows. */
    internal fun read(db: SupportSQLiteDatabase): Backup =
        Backup(
            blockedNumbers =
                readRows(db, "SELECT number, type, description, expiresAt FROM spam_numbers WHERE isUserBlocked = 1") {
                    BackupNumber(it.getString(0), it.getString(1), it.getString(2), it.longOrNull(3))
                },
            whitelistNumbers =
                readRows(db, "SELECT number, description, isEmergency, expiresAt, rangeDigits FROM whitelist") {
                    BackupWhitelist(it.getString(0), it.getString(1), it.getInt(2) != 0, it.longOrNull(3), it.getInt(4))
                },
            wildcardRules =
                readRows(
                    db,
                    "SELECT pattern, isRegex, description, enabled, scheduleDays, scheduleStartHour, scheduleEndHour FROM wildcard_rules",
                ) {
                    BackupWildcard(it.getString(0), it.getInt(1) != 0, it.getString(2), it.getInt(3) != 0, it.getInt(4), it.getInt(5), it.getInt(6))
                },
            keywordRules =
                readRows(
                    db,
                    "SELECT keyword, caseSensitive, description, enabled, scheduleDays, scheduleStartHour, scheduleEndHour FROM sms_keyword_rules",
                ) {
                    BackupKeyword(it.getString(0), it.getInt(1) != 0, it.getString(2), it.getInt(3) != 0, it.getInt(4), it.getInt(5), it.getInt(6))
                },
            rangeRules =
                readRows(
                    db,
                    "SELECT pattern, description, enabled, scheduleDays, scheduleStartHour, scheduleEndHour FROM hash_wildcard_rules",
                ) {
                    BackupRangeRule(it.getString(0), it.getString(1), it.getInt(2) != 0, it.getInt(3), it.getInt(4), it.getInt(5))
                },
        )

    private fun <T> readRows(
        db: SupportSQLiteDatabase,
        sql: String,
        row: (Cursor) -> T,
    ): List<T> =
        try {
            db.query(sql).use { cursor ->
                buildList { while (cursor.moveToNext()) add(row(cursor)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read from the damaged database: $sql", e)
            emptyList()
        }

    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

    private fun merge(
        first: Backup,
        second: Backup,
    ) = Backup(
        blockedNumbers = (first.blockedNumbers + second.blockedNumbers).distinct(),
        whitelistNumbers = (first.whitelistNumbers + second.whitelistNumbers).distinct(),
        wildcardRules = (first.wildcardRules + second.wildcardRules).distinct(),
        keywordRules = (first.keywordRules + second.keywordRules).distinct(),
        rangeRules = (first.rangeRules + second.rangeRules).distinct(),
    )

    private fun Backup.rowCount() = blockedNumbers.size + whitelistNumbers.size + wildcardRules.size + keywordRules.size + rangeRules.size

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    private fun readSaved(context: Context): Backup? {
        val source = file(context)
        if (!source.exists()) return null
        return try {
            BackupRestore.backupFromJson(source.readText())
        } catch (e: Exception) {
            Log.w(TAG, "The saved rows can't be read", e)
            null
        }
    }

    /** Written beside the target and moved over it, so a crash never leaves half a file. */
    private fun write(
        context: Context,
        backup: Backup,
    ) {
        val target = file(context)
        val temp = File(target.path + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(BackupRestore.backupToJson(backup).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
