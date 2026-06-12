package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full app backup/restore — settings, blocklist, whitelist, wildcard rules, call log.
 *
 * ## Versioning
 *
 * - **v1**: initial schema (numbers, whitelist).
 * - **v2**: added wildcard rules and keyword rules. Whitelist gained
 *   `isEmergency`.
 * - **v3** (v1.6.3): wildcard and keyword rules now carry their
 *   schedule columns (`scheduleDays`, `scheduleStartHour`,
 *   `scheduleEndHour`). Prior backups silently dropped the schedule,
 *   so a time-gated rule round-tripped through a v2 backup would fire
 *   24/7 after restore. The reader accepts v1–v3; the writer emits v3.
 *   Older backups that don't carry schedule fields are restored with
 *   all-zeros — the Kotlin defaults on [WildcardRule] and
 *   [SmsKeywordRule] treat that as "always active", preserving
 *   pre-v3 behavior.
 */
@Suppress("TooManyFunctions")
object BackupRestore {
    private const val MIN_IMPORTED_DIGITS = 5
    private const val CURRENT_BACKUP_VERSION = 3
    private const val OLDEST_SUPPORTED_VERSION = 1

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    data class Backup(
        val version: Int = CURRENT_BACKUP_VERSION,
        val app: String = "CallShield",
        val timestamp: Long = System.currentTimeMillis(),
        val blockedNumbers: List<BackupNumber> = emptyList(),
        val whitelistNumbers: List<BackupWhitelist> = emptyList(),
        val wildcardRules: List<BackupWildcard> = emptyList(),
        val keywordRules: List<BackupKeyword> = emptyList(),
    )

    data class BackupNumber(
        val number: String,
        val type: String,
        val description: String,
        val source: String,
    )

    data class BackupWhitelist(
        val number: String,
        val description: String,
        val isEmergency: Boolean = false,
    )

    data class BackupWildcard(
        val pattern: String,
        val isRegex: Boolean,
        val description: String,
        val enabled: Boolean,
        /**
         * Bitmask of active weekdays; see [TimeSchedule]. Defaults to
         * `0` for backward compatibility with pre-v3 backups, which is
         * interpreted as "always active".
         */
        val scheduleDays: Int = 0,
        val scheduleStartHour: Int = 0,
        val scheduleEndHour: Int = 0,
    )

    data class BackupKeyword(
        val keyword: String,
        val caseSensitive: Boolean,
        val description: String,
        val enabled: Boolean,
        val scheduleDays: Int = 0,
        val scheduleStartHour: Int = 0,
        val scheduleEndHour: Int = 0,
    )

    enum class RestoreMode { MERGE, REPLACE }

    data class RestoreCounts(
        val blockedNumbers: Int = 0,
        val whitelistNumbers: Int = 0,
        val wildcardRules: Int = 0,
        val keywordRules: Int = 0,
    ) {
        val total: Int
            get() = blockedNumbers + whitelistNumbers + wildcardRules + keywordRules
    }

    data class RestorePayload(
        val blockedNumbers: List<BackupNumber>,
        val whitelistNumbers: List<BackupWhitelist>,
        val wildcardRules: List<BackupWildcard>,
        val keywordRules: List<BackupKeyword>,
    ) {
        val counts: RestoreCounts
            get() =
                RestoreCounts(
                    blockedNumbers = blockedNumbers.size,
                    whitelistNumbers = whitelistNumbers.size,
                    wildcardRules = wildcardRules.size,
                    keywordRules = keywordRules.size,
                )
    }

    data class RestorePreview(
        val counts: RestoreCounts,
        val conflicts: RestoreCounts,
        val backupTimestamp: Long,
        val payload: RestorePayload,
    )

    data class RestorePreviewResult(
        val success: Boolean,
        val message: String,
        val preview: RestorePreview? = null,
    )

    data class RestoreResult(
        val success: Boolean,
        val message: String,
    )

    internal enum class RestoreFailure {
        INVALID_FORMAT,
        WRONG_APP,
        UNSUPPORTED_VERSION,
        EMPTY,
        NO_VALID_ITEMS,
    }

    internal sealed interface RestoreValidation {
        data class Valid(
            val payload: RestorePayload,
            val timestamp: Long,
        ) : RestoreValidation

        data class Invalid(
            val failure: RestoreFailure,
            val version: Int? = null,
        ) : RestoreValidation
    }

    suspend fun createBackup(context: Context): String =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).spamDao()

            val numbers =
                dao.getUserBlockedNumbers().first().map {
                    BackupNumber(it.number, it.type, it.description, it.source)
                }
            val whitelist =
                dao.getAllWhitelist().first().map {
                    BackupWhitelist(it.number, it.description, it.isEmergency)
                }
            val wildcards =
                dao.getAllWildcardRules().first().map {
                    BackupWildcard(
                        pattern = it.pattern,
                        isRegex = it.isRegex,
                        description = it.description,
                        enabled = it.enabled,
                        scheduleDays = it.scheduleDays,
                        scheduleStartHour = it.scheduleStartHour,
                        scheduleEndHour = it.scheduleEndHour,
                    )
                }
            val keywords =
                dao.getAllKeywordRules().first().map {
                    BackupKeyword(
                        keyword = it.keyword,
                        caseSensitive = it.caseSensitive,
                        description = it.description,
                        enabled = it.enabled,
                        scheduleDays = it.scheduleDays,
                        scheduleStartHour = it.scheduleStartHour,
                        scheduleEndHour = it.scheduleEndHour,
                    )
                }

            val backup =
                Backup(
                    blockedNumbers = numbers,
                    whitelistNumbers = whitelist,
                    wildcardRules = wildcards,
                    keywordRules = keywords,
                )

            val adapter = moshi.adapter(Backup::class.java).indent("  ")
            adapter.toJson(backup)
        }

    suspend fun shareBackup(context: Context) {
        val json = createBackup(context)
        val chooserIntent =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "backups")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "callshield_backup.json")
                file.writeText(json)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.backup_subject))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                Intent.createChooser(intent, context.getString(R.string.backup_chooser_title)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
    }

    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            val previewResult = previewRestoreFromUri(context, uri)
            val preview = previewResult.preview
            if (!previewResult.success || preview == null) {
                return@withContext RestoreResult(false, previewResult.message)
            }
            restoreFromPreview(context, preview, RestoreMode.MERGE)
        }

    suspend fun previewRestoreFromUri(
        context: Context,
        uri: Uri,
    ): RestorePreviewResult =
        withContext(Dispatchers.IO) {
            try {
                val json =
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: return@withContext RestorePreviewResult(
                        false,
                        context.getString(R.string.backup_restore_could_not_read),
                    )

                previewRestoreJson(context, json, AppDatabase.getInstance(context).spamDao())
            } catch (e: Exception) {
                RestorePreviewResult(false, context.getString(R.string.backup_restore_error, e.message ?: ""))
            }
        }

    suspend fun restoreFromPreview(
        context: Context,
        preview: RestorePreview,
        mode: RestoreMode,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            restorePayload(
                context = context,
                payload = preview.payload,
                mode = mode,
                dao = AppDatabase.getInstance(context).spamDao(),
                repo = SpamRepository.getInstance(context),
            )
        }

    internal suspend fun previewRestoreJson(
        context: Context,
        json: String,
        dao: SpamDao,
    ): RestorePreviewResult =
        when (val validation = validateBackupJson(json)) {
            is RestoreValidation.Invalid -> {
                RestorePreviewResult(false, validation.message(context))
            }

            is RestoreValidation.Valid -> {
                val conflicts = countConflicts(dao, validation.payload)
                val preview =
                    RestorePreview(
                        counts = validation.payload.counts,
                        conflicts = conflicts,
                        backupTimestamp = validation.timestamp,
                        payload = validation.payload,
                    )
                RestorePreviewResult(
                    true,
                    context.getString(
                        R.string.backup_restore_preview_ready,
                        preview.counts.blockedNumbers,
                        preview.counts.whitelistNumbers,
                        preview.counts.wildcardRules,
                        preview.counts.keywordRules,
                    ),
                    preview,
                )
            }
        }

    @Suppress("LongMethod")
    internal suspend fun restorePayload(
        context: Context,
        payload: RestorePayload,
        mode: RestoreMode,
        dao: SpamDao,
        repo: SpamRepository,
    ): RestoreResult =
        try {
            if (mode == RestoreMode.REPLACE) {
                dao.clearBackupRestorableData()
            }

            var numbersRestored = 0
            for (n in payload.blockedNumbers) {
                repo.blockNumber(n.number, n.type, n.description)
                numbersRestored++
            }

            var whitelistRestored = 0
            for (w in payload.whitelistNumbers) {
                repo.addToWhitelist(w.number, w.description, isEmergency = w.isEmergency)
                whitelistRestored++
            }

            var rulesRestored = 0
            for (r in payload.wildcardRules) {
                dao.insertWildcardRule(
                    WildcardRule(
                        pattern = r.pattern,
                        isRegex = r.isRegex,
                        description = r.description,
                        enabled = r.enabled,
                        scheduleDays = r.scheduleDays,
                        scheduleStartHour = r.scheduleStartHour,
                        scheduleEndHour = r.scheduleEndHour,
                    ),
                )
                rulesRestored++
            }

            var keywordsRestored = 0
            for (k in payload.keywordRules) {
                dao.insertKeywordRule(
                    SmsKeywordRule(
                        keyword = k.keyword,
                        caseSensitive = k.caseSensitive,
                        description = k.description,
                        enabled = k.enabled,
                        scheduleDays = k.scheduleDays,
                        scheduleStartHour = k.scheduleStartHour,
                        scheduleEndHour = k.scheduleEndHour,
                    ),
                )
                keywordsRestored++
            }

            repo.invalidateRestoredRuleCaches()

            RestoreResult(
                true,
                context.getString(
                    if (mode == RestoreMode.REPLACE) {
                        R.string.backup_restore_success_replace
                    } else {
                        R.string.backup_restore_success_merge
                    },
                    numbersRestored,
                    whitelistRestored,
                    rulesRestored,
                    keywordsRestored,
                ),
            )
        } catch (e: Exception) {
            RestoreResult(false, context.getString(R.string.backup_restore_error, e.message ?: ""))
        }

    internal fun validateBackupJson(json: String): RestoreValidation =
        try {
            val backup = moshi.adapter(Backup::class.java).fromJson(json)
            validateBackupForRestore(backup)
        } catch (_: Exception) {
            RestoreValidation.Invalid(RestoreFailure.INVALID_FORMAT)
        }

    @Suppress("ReturnCount")
    internal fun validateBackupForRestore(backup: Backup?): RestoreValidation {
        if (backup == null) return RestoreValidation.Invalid(RestoreFailure.INVALID_FORMAT)
        if (backup.app != "CallShield") return RestoreValidation.Invalid(RestoreFailure.WRONG_APP)
        if (backup.version !in OLDEST_SUPPORTED_VERSION..CURRENT_BACKUP_VERSION) {
            return RestoreValidation.Invalid(RestoreFailure.UNSUPPORTED_VERSION, backup.version)
        }
        if (
            backup.blockedNumbers.isEmpty() &&
            backup.whitelistNumbers.isEmpty() &&
            backup.wildcardRules.isEmpty() &&
            backup.keywordRules.isEmpty()
        ) {
            return RestoreValidation.Invalid(RestoreFailure.EMPTY)
        }

        val payload = backup.toRestorePayload()
        if (payload.counts.total == 0) {
            return RestoreValidation.Invalid(RestoreFailure.NO_VALID_ITEMS)
        }
        return RestoreValidation.Valid(payload, backup.timestamp)
    }

    internal fun backupToJson(backup: Backup): String = moshi.adapter(Backup::class.java).toJson(backup)

    private fun Backup.toRestorePayload(): RestorePayload =
        RestorePayload(
            blockedNumbers =
                blockedNumbers.mapNotNull { number ->
                    val normalizedNumber = normalizeImportedNumber(number.number) ?: return@mapNotNull null
                    BackupNumber(
                        number = normalizedNumber,
                        type = number.type.trim().ifBlank { "unknown" },
                        description = number.description.trim(),
                        source = number.source.trim().ifBlank { "user" },
                    )
                },
            whitelistNumbers =
                whitelistNumbers.mapNotNull { whitelist ->
                    val normalizedNumber = normalizeImportedNumber(whitelist.number) ?: return@mapNotNull null
                    BackupWhitelist(
                        number = normalizedNumber,
                        description = whitelist.description.trim(),
                        isEmergency = whitelist.isEmergency,
                    )
                },
            wildcardRules =
                wildcardRules.mapNotNull { rule ->
                    val trimmedPattern = rule.pattern.trim()
                    if (trimmedPattern.isBlank()) return@mapNotNull null
                    BackupWildcard(
                        pattern = trimmedPattern,
                        isRegex = rule.isRegex,
                        description = rule.description.trim(),
                        enabled = rule.enabled,
                        scheduleDays = sanitizeScheduleDays(rule.scheduleDays),
                        scheduleStartHour = sanitizeScheduleHour(rule.scheduleStartHour),
                        scheduleEndHour = sanitizeScheduleHour(rule.scheduleEndHour),
                    )
                },
            keywordRules =
                keywordRules.mapNotNull { rule ->
                    val trimmedKeyword = rule.keyword.trim()
                    if (trimmedKeyword.isBlank()) return@mapNotNull null
                    BackupKeyword(
                        keyword = trimmedKeyword,
                        caseSensitive = rule.caseSensitive,
                        description = rule.description.trim(),
                        enabled = rule.enabled,
                        scheduleDays = sanitizeScheduleDays(rule.scheduleDays),
                        scheduleStartHour = sanitizeScheduleHour(rule.scheduleStartHour),
                        scheduleEndHour = sanitizeScheduleHour(rule.scheduleEndHour),
                    )
                },
        )

    private suspend fun countConflicts(
        dao: SpamDao,
        payload: RestorePayload,
    ): RestoreCounts {
        val existingBlockRows = dao.getUserBlockedNumbersSync()
        val existingWhitelistRows = dao.getAllWhitelist().first()
        val existingWildcardRows = dao.getAllWildcardRules().first()
        val existingKeywordRows = dao.getAllKeywordRules().first()
        val existingBlocks = existingBlockRows.map { it.number }.toSet()
        val existingWhitelist = existingWhitelistRows.map { it.number }.toSet()
        val existingWildcards = existingWildcardRows.map { it.pattern to it.isRegex }.toSet()
        val existingKeywords = existingKeywordRows.map { it.keyword to it.caseSensitive }.toSet()

        return RestoreCounts(
            blockedNumbers = payload.blockedNumbers.count { it.number in existingBlocks },
            whitelistNumbers = payload.whitelistNumbers.count { it.number in existingWhitelist },
            wildcardRules = payload.wildcardRules.count { (it.pattern to it.isRegex) in existingWildcards },
            keywordRules = payload.keywordRules.count { (it.keyword to it.caseSensitive) in existingKeywords },
        )
    }

    private fun RestoreValidation.Invalid.message(context: Context): String =
        when (failure) {
            RestoreFailure.INVALID_FORMAT -> {
                context.getString(R.string.backup_restore_invalid_format)
            }

            RestoreFailure.WRONG_APP -> {
                context.getString(R.string.backup_restore_wrong_app)
            }

            RestoreFailure.UNSUPPORTED_VERSION -> {
                context.getString(R.string.backup_restore_unsupported_version, version ?: -1)
            }

            RestoreFailure.EMPTY -> {
                context.getString(R.string.backup_restore_no_data)
            }

            RestoreFailure.NO_VALID_ITEMS -> {
                context.getString(R.string.backup_restore_no_valid_items)
            }
        }

    private fun normalizeImportedNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in MIN_IMPORTED_DIGITS..15) {
            return null
        }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Reject corrupt schedule values from a hostile or malformed backup.
     * Only the low 7 bits are meaningful (one per weekday); anything else
     * is silently zeroed so the restored rule falls back to "always active"
     * rather than getting a surprising gated schedule.
     */
    private fun sanitizeScheduleDays(raw: Int): Int = raw and 0b1111111

    private fun sanitizeScheduleHour(raw: Int): Int = raw.coerceIn(0, 23)
}
