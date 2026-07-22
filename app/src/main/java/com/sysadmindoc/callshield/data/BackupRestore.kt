package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.Preferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.util.filterAsciiDigits
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
 *   24/7 after restore.
 * - **v4**: added selective sections, range rules, non-secret settings,
 *   and blocked-call/SMS logs. The reader accepts v1-v4; the writer emits v4.
 *   Older backups that don't carry schedule fields are restored with
 *   all-zeros — the Kotlin defaults on [WildcardRule] and
 *   [SmsKeywordRule] treat that as "always active", preserving
 *   pre-v3 behavior.
 */
@Suppress("TooManyFunctions")
object BackupRestore {
    private const val MIN_IMPORTED_DIGITS = 5
    private const val CURRENT_BACKUP_VERSION = 4
    private const val OLDEST_SUPPORTED_VERSION = 1
    internal const val MAX_BACKUP_RESTORE_ROWS = MAX_IMPORT_ROWS

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    enum class BackupSection {
        BLOCKED_NUMBERS,
        WHITELIST,
        WILDCARD_RULES,
        RANGE_RULES,
        KEYWORD_RULES,
        SETTINGS,
        LOGS,
    }

    val defaultExportSections: Set<BackupSection> =
        setOf(
            BackupSection.BLOCKED_NUMBERS,
            BackupSection.WHITELIST,
            BackupSection.WILDCARD_RULES,
            BackupSection.RANGE_RULES,
            BackupSection.KEYWORD_RULES,
            BackupSection.SETTINGS,
        )

    val defaultRestoreSections: Set<BackupSection> = defaultExportSections

    data class Backup(
        val version: Int = CURRENT_BACKUP_VERSION,
        val app: String = "CallShield",
        val timestamp: Long = System.currentTimeMillis(),
        val blockedNumbers: List<BackupNumber> = emptyList(),
        val whitelistNumbers: List<BackupWhitelist> = emptyList(),
        val wildcardRules: List<BackupWildcard> = emptyList(),
        val keywordRules: List<BackupKeyword> = emptyList(),
        val rangeRules: List<BackupRangeRule> = emptyList(),
        val settings: BackupSettings? = null,
        val logs: List<BackupLogEntry> = emptyList(),
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

    data class BackupRangeRule(
        val pattern: String,
        val description: String,
        val enabled: Boolean,
        val scheduleDays: Int = 0,
        val scheduleStartHour: Int = 0,
        val scheduleEndHour: Int = 0,
    )

    @Suppress("LongParameterList")
    data class BackupSettings(
        val blockCallsEnabled: Boolean = true,
        val blockSmsEnabled: Boolean = true,
        val blockUnknownEnabled: Boolean = false,
        val stirShakenEnabled: Boolean = true,
        val stirTrustedAllowEnabled: Boolean = true,
        val autoMuteLowConfidenceEnabled: Boolean = false,
        val neighborSpoofEnabled: Boolean = true,
        val heuristicsEnabled: Boolean = true,
        val smsContentEnabled: Boolean = true,
        val smsBurstEnabled: Boolean = true,
        val urlhausStripQueryEnabled: Boolean = true,
        val contactWhitelistEnabled: Boolean = true,
        val contactsOnlyEnabled: Boolean = false,
        val dbPrefixExpansionEnabled: Boolean = false,
        val aggressiveModeEnabled: Boolean = false,
        val answeredCallerTrustEnabled: Boolean = true,
        val answeredCallerThreshold: Int = CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD,
        val answeredCallerWindowDays: Int = CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
        val emergencyCallbackGraceEnabled: Boolean = true,
        val emergencyCallbackWindowMinutes: Int = CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
        val timeBlockEnabled: Boolean = false,
        val timeBlockStartHour: Int = 22,
        val timeBlockEndHour: Int = 7,
        val frequencyEscalationEnabled: Boolean = true,
        val frequencyThreshold: Int = 3,
        val autoCleanupEnabled: Boolean = false,
        val cleanupDays: Int = 30,
        val mlScorerEnabled: Boolean = true,
        val rcsFilterEnabled: Boolean = true,
        val postCallScreenEnabled: Boolean = false,
        val silentVoicemailEnabled: Boolean = false,
        val pushAlertEnabled: Boolean = true,
        val pushAlertDisabledPackages: List<String> = emptyList(),
        val regionBlockEnabled: Boolean = false,
        val allowedRegions: List<String> = emptyList(),
        val cnapTrustPatterns: List<String> = emptyList(),
        val cnapBlockPatterns: List<String> = emptyList(),
        val activeProfileName: String? = null,
    )

    @Suppress("LongParameterList")
    data class BackupLogEntry(
        val number: String,
        val timestamp: Long,
        val type: String = "unknown",
        val wasBlocked: Boolean = true,
        val isCall: Boolean = true,
        val smsBody: String? = null,
        val matchReason: String = "",
        val confidence: Int = 100,
        val logKey: String? = null,
    )

    enum class RestoreMode { MERGE, REPLACE }

    data class RestoreCounts(
        val blockedNumbers: Int = 0,
        val whitelistNumbers: Int = 0,
        val wildcardRules: Int = 0,
        val keywordRules: Int = 0,
        val rangeRules: Int = 0,
        val settings: Int = 0,
        val logs: Int = 0,
    ) {
        val total: Int
            get() = blockedNumbers + whitelistNumbers + wildcardRules + keywordRules + rangeRules + settings + logs
    }

    data class RestorePayload(
        val blockedNumbers: List<BackupNumber> = emptyList(),
        val whitelistNumbers: List<BackupWhitelist> = emptyList(),
        val wildcardRules: List<BackupWildcard> = emptyList(),
        val keywordRules: List<BackupKeyword> = emptyList(),
        val rangeRules: List<BackupRangeRule> = emptyList(),
        val settings: BackupSettings? = null,
        val logs: List<BackupLogEntry> = emptyList(),
    ) {
        val counts: RestoreCounts
            get() =
                RestoreCounts(
                    blockedNumbers = blockedNumbers.size,
                    whitelistNumbers = whitelistNumbers.size,
                    wildcardRules = wildcardRules.size,
                    keywordRules = keywordRules.size,
                    rangeRules = rangeRules.size,
                    settings = if (settings != null) 1 else 0,
                    logs = logs.size,
                )
    }

    data class RestorePreview(
        val counts: RestoreCounts,
        val conflicts: RestoreCounts,
        val backupTimestamp: Long,
        val payload: RestorePayload,
        val selectedSections: Set<BackupSection> = defaultRestoreSections,
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
        TOO_MANY_ITEMS,
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

    @Suppress("LongMethod")
    suspend fun createBackup(
        context: Context,
        sections: Set<BackupSection> = defaultExportSections,
    ): String =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).spamDao()
            val repo = SpamRepository.getInstance(context)

            val numbers =
                if (BackupSection.BLOCKED_NUMBERS in sections) {
                    dao.getUserBlockedNumbers().first().map {
                        BackupNumber(it.number, it.type, it.description, it.source)
                    }
                } else {
                    emptyList()
                }
            val whitelist =
                if (BackupSection.WHITELIST in sections) {
                    dao.getAllWhitelist().first().map {
                        BackupWhitelist(it.number, it.description, it.isEmergency)
                    }
                } else {
                    emptyList()
                }
            val wildcards =
                if (BackupSection.WILDCARD_RULES in sections) {
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
                } else {
                    emptyList()
                }
            val keywords =
                if (BackupSection.KEYWORD_RULES in sections) {
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
                } else {
                    emptyList()
                }
            val ranges =
                if (BackupSection.RANGE_RULES in sections) {
                    dao.getAllHashWildcardRules().first().map {
                        BackupRangeRule(
                            pattern = it.pattern,
                            description = it.description,
                            enabled = it.enabled,
                            scheduleDays = it.scheduleDays,
                            scheduleStartHour = it.scheduleStartHour,
                            scheduleEndHour = it.scheduleEndHour,
                        )
                    }
                } else {
                    emptyList()
                }
            val settings =
                if (BackupSection.SETTINGS in sections) {
                    repo.readPrefsSnapshot().toBackupSettings()
                } else {
                    null
                }
            val logs =
                if (BackupSection.LOGS in sections) {
                    dao.getBlockedCalls().first().map {
                        BackupLogEntry(
                            number = it.number,
                            timestamp = it.timestamp,
                            type = it.type,
                            wasBlocked = it.wasBlocked,
                            isCall = it.isCall,
                            smsBody = it.smsBody,
                            matchReason = it.matchReason,
                            confidence = it.confidence,
                            logKey = it.logKey,
                        )
                    }
                } else {
                    emptyList()
                }

            val backup =
                Backup(
                    blockedNumbers = numbers,
                    whitelistNumbers = whitelist,
                    wildcardRules = wildcards,
                    keywordRules = keywords,
                    rangeRules = ranges,
                    settings = settings,
                    logs = logs,
                )

            val adapter = moshi.adapter(Backup::class.java).indent("  ")
            adapter.toJson(backup)
        }

    suspend fun shareBackup(
        context: Context,
        sections: Set<BackupSection> = defaultExportSections,
    ) {
        val json = createBackup(context, sections)
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
        sections: Set<BackupSection> = defaultRestoreSections,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            val previewResult = previewRestoreFromUri(context, uri, sections)
            val preview = previewResult.preview
            if (!previewResult.success || preview == null) {
                return@withContext RestoreResult(false, previewResult.message)
            }
            restoreFromPreview(context, preview, RestoreMode.MERGE)
        }

    suspend fun previewRestoreFromUri(
        context: Context,
        uri: Uri,
        sections: Set<BackupSection> = defaultRestoreSections,
    ): RestorePreviewResult =
        withContext(Dispatchers.IO) {
            try {
                // Bounded read — a huge/hostile backup file is rejected rather
                // than materialized whole into memory before validation.
                val json =
                    context.contentResolver.openInputStream(uri)?.readTextBounded()
                        ?: return@withContext RestorePreviewResult(
                            false,
                            context.getString(R.string.backup_restore_could_not_read),
                        )

                previewRestoreJson(context, json, AppDatabase.getInstance(context).spamDao(), sections)
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
                selectedSections = preview.selectedSections,
            )
        }

    internal suspend fun previewRestoreJson(
        context: Context,
        json: String,
        dao: SpamDao,
        sections: Set<BackupSection> = defaultRestoreSections,
    ): RestorePreviewResult =
        when (val validation = validateBackupJson(json, sections)) {
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
                        selectedSections = sections,
                    )
                RestorePreviewResult(
                    true,
                    context.getString(
                        R.string.backup_restore_preview_ready,
                        preview.counts.blockedNumbers,
                        preview.counts.whitelistNumbers,
                        preview.counts.wildcardRules,
                        preview.counts.keywordRules,
                        preview.counts.rangeRules,
                        preview.counts.settings,
                        preview.counts.logs,
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
        selectedSections: Set<BackupSection> = defaultRestoreSections,
    ): RestoreResult =
        try {
            var numbersRestored = 0
            var whitelistRestored = 0
            var rulesRestored = 0
            var keywordsRestored = 0
            var rangesRestored = 0
            var logsRestored = 0

            // Atomic: in REPLACE mode the clear and every re-insert run in a
            // single Room transaction so a mid-restore failure rolls back to the
            // pre-restore state instead of leaving the user's data half-cleared
            // and half-restored (data loss). Settings live in DataStore, not
            // Room, so they are applied after the transaction commits.
            repo.runInTransaction {
                if (mode == RestoreMode.REPLACE) {
                    clearSelectedBackupSections(dao, selectedSections)
                }

                for (n in payload.blockedNumbers) {
                    repo.blockNumber(n.number, n.type, n.description)
                    numbersRestored++
                }

                for (w in payload.whitelistNumbers) {
                    repo.addToWhitelist(w.number, w.description, isEmergency = w.isEmergency)
                    whitelistRestored++
                }

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

                for (r in payload.rangeRules) {
                    dao.insertHashWildcardRule(
                        HashWildcardRule(
                            pattern = r.pattern,
                            description = r.description,
                            enabled = r.enabled,
                            scheduleDays = r.scheduleDays,
                            scheduleStartHour = r.scheduleStartHour,
                            scheduleEndHour = r.scheduleEndHour,
                        ),
                    )
                    rangesRestored++
                }

                for (log in payload.logs) {
                    dao.insertBlockedCall(
                        BlockedCall(
                            number = log.number,
                            timestamp = log.timestamp,
                            type = log.type,
                            wasBlocked = log.wasBlocked,
                            isCall = log.isCall,
                            smsBody = log.smsBody,
                            matchReason = log.matchReason,
                            confidence = log.confidence,
                            logKey = log.logKey,
                        ),
                    )
                    logsRestored++
                }
            }

            var settingsRestored = 0
            payload.settings?.let {
                it.applyTo(repo)
                settingsRestored = 1
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
                    rangesRestored,
                    settingsRestored,
                    logsRestored,
                ),
            )
        } catch (e: Exception) {
            RestoreResult(false, context.getString(R.string.backup_restore_error, e.message ?: ""))
        }

    internal fun validateBackupJson(
        json: String,
        sections: Set<BackupSection> = defaultRestoreSections,
    ): RestoreValidation =
        try {
            val backup = moshi.adapter(Backup::class.java).fromJson(json)
            validateBackupForRestore(backup, sections)
        } catch (_: Exception) {
            RestoreValidation.Invalid(RestoreFailure.INVALID_FORMAT)
        }

    @Suppress("ReturnCount")
    internal fun validateBackupForRestore(
        backup: Backup?,
        sections: Set<BackupSection> = defaultRestoreSections,
    ): RestoreValidation {
        if (backup == null) return RestoreValidation.Invalid(RestoreFailure.INVALID_FORMAT)
        if (backup.app != "CallShield") return RestoreValidation.Invalid(RestoreFailure.WRONG_APP)
        if (backup.version !in OLDEST_SUPPORTED_VERSION..CURRENT_BACKUP_VERSION) {
            return RestoreValidation.Invalid(RestoreFailure.UNSUPPORTED_VERSION, backup.version)
        }
        if (
            backup.blockedNumbers.isEmpty() &&
            backup.whitelistNumbers.isEmpty() &&
            backup.wildcardRules.isEmpty() &&
            backup.keywordRules.isEmpty() &&
            backup.rangeRules.isEmpty() &&
            backup.settings == null &&
            backup.logs.isEmpty()
        ) {
            return RestoreValidation.Invalid(RestoreFailure.EMPTY)
        }
        if (backup.rawItemCount() > MAX_BACKUP_RESTORE_ROWS.toLong()) {
            return RestoreValidation.Invalid(RestoreFailure.TOO_MANY_ITEMS)
        }

        val payload = backup.toRestorePayload(sections)
        if (payload.counts.total == 0) {
            return RestoreValidation.Invalid(RestoreFailure.NO_VALID_ITEMS)
        }
        return RestoreValidation.Valid(payload, backup.timestamp)
    }

    internal fun backupToJson(backup: Backup): String = moshi.adapter(Backup::class.java).toJson(backup)

    private fun Backup.rawItemCount(): Long =
        blockedNumbers.size.toLong() +
            whitelistNumbers.size +
            wildcardRules.size +
            keywordRules.size +
            rangeRules.size +
            logs.size +
            if (settings != null) 1 else 0

    private fun Backup.toRestorePayload(sections: Set<BackupSection>): RestorePayload =
        RestorePayload(
            blockedNumbers =
                if (BackupSection.BLOCKED_NUMBERS in sections) {
                    blockedNumbers.mapNotNull { number ->
                        val normalizedNumber = normalizeImportedNumber(number.number) ?: return@mapNotNull null
                        BackupNumber(
                            number = normalizedNumber,
                            type = number.type.trim().ifBlank { "unknown" },
                            description = number.description.trim(),
                            source = number.source.trim().ifBlank { "user" },
                        )
                    }
                } else {
                    emptyList()
                },
            whitelistNumbers =
                if (BackupSection.WHITELIST in sections) {
                    whitelistNumbers.mapNotNull { whitelist ->
                        val normalizedNumber = normalizeImportedNumber(whitelist.number) ?: return@mapNotNull null
                        BackupWhitelist(
                            number = normalizedNumber,
                            description = whitelist.description.trim(),
                            isEmergency = whitelist.isEmergency,
                        )
                    }
                } else {
                    emptyList()
                },
            wildcardRules =
                if (BackupSection.WILDCARD_RULES in sections) {
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
                    }
                } else {
                    emptyList()
                },
            keywordRules =
                if (BackupSection.KEYWORD_RULES in sections) {
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
                    }
                } else {
                    emptyList()
                },
            rangeRules =
                if (BackupSection.RANGE_RULES in sections) {
                    rangeRules.mapNotNull { rule ->
                        val trimmedPattern = rule.pattern.trim()
                        if (trimmedPattern.isBlank()) return@mapNotNull null
                        BackupRangeRule(
                            pattern = trimmedPattern,
                            description = rule.description.trim(),
                            enabled = rule.enabled,
                            scheduleDays = sanitizeScheduleDays(rule.scheduleDays),
                            scheduleStartHour = sanitizeScheduleHour(rule.scheduleStartHour),
                            scheduleEndHour = sanitizeScheduleHour(rule.scheduleEndHour),
                        )
                    }
                } else {
                    emptyList()
                },
            settings = if (BackupSection.SETTINGS in sections) settings?.sanitized() else null,
            logs =
                if (BackupSection.LOGS in sections) {
                    logs.mapNotNull { log ->
                        val normalizedNumber = normalizeImportedNumber(log.number) ?: return@mapNotNull null
                        BackupLogEntry(
                            number = normalizedNumber,
                            timestamp = log.timestamp.coerceAtLeast(0L),
                            type = log.type.trim().ifBlank { "unknown" },
                            wasBlocked = log.wasBlocked,
                            isCall = log.isCall,
                            smsBody = log.smsBody,
                            matchReason = log.matchReason.trim(),
                            confidence = log.confidence.coerceIn(0, 100),
                            logKey = log.logKey?.trim()?.ifBlank { null },
                        )
                    }
                } else {
                    emptyList()
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
        val existingRangeRows = dao.getAllHashWildcardRules().first()
        val existingLogRows = dao.getBlockedCalls().first()
        val existingBlocks = existingBlockRows.map { it.number }.toSet()
        val existingWhitelist = existingWhitelistRows.map { it.number }.toSet()
        val existingWildcards = existingWildcardRows.map { it.pattern to it.isRegex }.toSet()
        val existingKeywords = existingKeywordRows.map { it.keyword to it.caseSensitive }.toSet()
        val existingRanges = existingRangeRows.map { it.pattern }.toSet()
        val existingLogs = existingLogRows.map { it.conflictKey }.toSet()

        return RestoreCounts(
            blockedNumbers = payload.blockedNumbers.count { it.number in existingBlocks },
            whitelistNumbers = payload.whitelistNumbers.count { it.number in existingWhitelist },
            wildcardRules = payload.wildcardRules.count { (it.pattern to it.isRegex) in existingWildcards },
            keywordRules = payload.keywordRules.count { (it.keyword to it.caseSensitive) in existingKeywords },
            rangeRules = payload.rangeRules.count { it.pattern in existingRanges },
            settings = if (payload.settings != null) 1 else 0,
            logs = payload.logs.count { it.conflictKey in existingLogs },
        )
    }

    private suspend fun clearSelectedBackupSections(
        dao: SpamDao,
        sections: Set<BackupSection>,
    ) {
        if (BackupSection.BLOCKED_NUMBERS in sections) {
            dao.clearUserBlockFlagsOnSyncedNumbers()
            dao.deleteUserOwnedBlockedNumbers()
        }
        if (BackupSection.WHITELIST in sections) dao.clearWhitelist()
        if (BackupSection.WILDCARD_RULES in sections) dao.clearWildcardRules()
        if (BackupSection.RANGE_RULES in sections) dao.clearHashWildcardRules()
        if (BackupSection.KEYWORD_RULES in sections) dao.clearKeywordRules()
        if (BackupSection.LOGS in sections) dao.clearCallLog()
    }

    private fun Preferences.toBackupSettings(): BackupSettings =
        BackupSettings(
            blockCallsEnabled = this[SpamRepository.KEY_BLOCK_CALLS] ?: true,
            blockSmsEnabled = this[SpamRepository.KEY_BLOCK_SMS] ?: true,
            blockUnknownEnabled = this[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false,
            stirShakenEnabled = this[SpamRepository.KEY_STIR_SHAKEN] ?: true,
            stirTrustedAllowEnabled = this[SpamRepository.KEY_STIR_TRUSTED_ALLOW] ?: true,
            autoMuteLowConfidenceEnabled = this[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false,
            neighborSpoofEnabled = this[SpamRepository.KEY_NEIGHBOR_SPOOF] ?: true,
            heuristicsEnabled = this[SpamRepository.KEY_HEURISTICS] ?: true,
            smsContentEnabled = this[SpamRepository.KEY_SMS_CONTENT] ?: true,
            smsBurstEnabled = this[SpamRepository.KEY_SMS_BURST] ?: true,
            urlhausStripQueryEnabled = this[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true,
            contactWhitelistEnabled = this[SpamRepository.KEY_CONTACT_WHITELIST] ?: true,
            contactsOnlyEnabled = this[SpamRepository.KEY_CONTACTS_ONLY] ?: false,
            dbPrefixExpansionEnabled = this[SpamRepository.KEY_DB_PREFIX_EXPANSION] ?: false,
            aggressiveModeEnabled = this[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false,
            answeredCallerTrustEnabled = this[SpamRepository.KEY_ANSWERED_CALLER_TRUST] ?: true,
            answeredCallerThreshold =
                this[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD]
                    ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD,
            answeredCallerWindowDays =
                this[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS]
                    ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
            emergencyCallbackGraceEnabled = this[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] ?: true,
            emergencyCallbackWindowMinutes =
                this[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES]
                    ?: CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
            timeBlockEnabled = this[SpamRepository.KEY_TIME_BLOCK] ?: false,
            timeBlockStartHour = this[SpamRepository.KEY_TIME_BLOCK_START] ?: 22,
            timeBlockEndHour = this[SpamRepository.KEY_TIME_BLOCK_END] ?: 7,
            frequencyEscalationEnabled = this[SpamRepository.KEY_FREQ_ESCALATION] ?: true,
            frequencyThreshold = this[SpamRepository.KEY_FREQ_THRESHOLD] ?: 3,
            autoCleanupEnabled = this[SpamRepository.KEY_AUTO_CLEANUP] ?: false,
            cleanupDays = this[SpamRepository.KEY_CLEANUP_DAYS] ?: 30,
            mlScorerEnabled = this[SpamRepository.KEY_ML_SCORER] ?: true,
            rcsFilterEnabled = this[SpamRepository.KEY_RCS_FILTER] ?: true,
            postCallScreenEnabled = this[SpamRepository.KEY_POST_CALL_SCREEN] ?: false,
            silentVoicemailEnabled = this[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false,
            pushAlertEnabled = this[SpamRepository.KEY_PUSH_ALERT] ?: true,
            pushAlertDisabledPackages = (this[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet()).sorted(),
            regionBlockEnabled = this[SpamRepository.KEY_REGION_BLOCK] ?: false,
            allowedRegions =
                RegionRules.normalizeRegionCodes(this[SpamRepository.KEY_ALLOWED_REGIONS].orEmpty()).sorted(),
            cnapTrustPatterns =
                RegionRules.normalizeNamePatterns(this[SpamRepository.KEY_CNAP_TRUST_PATTERNS].orEmpty()).sorted(),
            cnapBlockPatterns =
                RegionRules.normalizeNamePatterns(this[SpamRepository.KEY_CNAP_BLOCK_PATTERNS].orEmpty()).sorted(),
            activeProfileName = this[SpamRepository.KEY_ACTIVE_PROFILE],
        )

    private fun BackupSettings.sanitized(): BackupSettings =
        copy(
            answeredCallerThreshold = answeredCallerThreshold.coerceIn(1, 10),
            answeredCallerWindowDays = answeredCallerWindowDays.coerceIn(1, 365),
            emergencyCallbackWindowMinutes = emergencyCallbackWindowMinutes.coerceIn(15, 360),
            timeBlockStartHour = sanitizeScheduleHour(timeBlockStartHour),
            timeBlockEndHour = sanitizeScheduleHour(timeBlockEndHour),
            frequencyThreshold = frequencyThreshold.coerceIn(1, 25),
            cleanupDays = cleanupDays.coerceIn(1, 365),
            pushAlertDisabledPackages =
                pushAlertDisabledPackages
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),
            allowedRegions = RegionRules.normalizeRegionCodes(allowedRegions).sorted(),
            cnapTrustPatterns = RegionRules.normalizeNamePatterns(cnapTrustPatterns).sorted(),
            cnapBlockPatterns = RegionRules.normalizeNamePatterns(cnapBlockPatterns).sorted(),
            activeProfileName = activeProfileName?.trim()?.ifBlank { null },
        )

    @Suppress("LongMethod")
    private suspend fun BackupSettings.applyTo(repo: SpamRepository) {
        val sanitized = sanitized()
        repo.setBlockCalls(sanitized.blockCallsEnabled)
        repo.setBlockSms(sanitized.blockSmsEnabled)
        repo.setBlockUnknown(sanitized.blockUnknownEnabled)
        repo.setStirShaken(sanitized.stirShakenEnabled)
        repo.setStirTrustedAllow(sanitized.stirTrustedAllowEnabled)
        repo.setAutoMuteLowConfidence(sanitized.autoMuteLowConfidenceEnabled)
        repo.setNeighborSpoof(sanitized.neighborSpoofEnabled)
        repo.setHeuristics(sanitized.heuristicsEnabled)
        repo.setSmsContent(sanitized.smsContentEnabled)
        repo.setSmsBurst(sanitized.smsBurstEnabled)
        repo.setUrlhausStripQuery(sanitized.urlhausStripQueryEnabled)
        repo.setContactWhitelist(sanitized.contactWhitelistEnabled)
        repo.setContactsOnly(sanitized.contactsOnlyEnabled)
        repo.setDbPrefixExpansion(sanitized.dbPrefixExpansionEnabled)
        repo.setAggressiveMode(sanitized.aggressiveModeEnabled)
        repo.setAnsweredCallerTrust(sanitized.answeredCallerTrustEnabled)
        repo.setAnsweredCallerThreshold(sanitized.answeredCallerThreshold)
        repo.setAnsweredCallerWindowDays(sanitized.answeredCallerWindowDays)
        repo.setEmergencyCallbackGrace(sanitized.emergencyCallbackGraceEnabled)
        repo.setEmergencyCallbackWindowMinutes(sanitized.emergencyCallbackWindowMinutes)
        repo.setTimeBlock(sanitized.timeBlockEnabled)
        repo.setTimeBlockStart(sanitized.timeBlockStartHour)
        repo.setTimeBlockEnd(sanitized.timeBlockEndHour)
        repo.setFreqEscalation(sanitized.frequencyEscalationEnabled)
        repo.setFreqThreshold(sanitized.frequencyThreshold)
        repo.setAutoCleanup(sanitized.autoCleanupEnabled)
        repo.setCleanupDays(sanitized.cleanupDays)
        repo.setMlScorer(sanitized.mlScorerEnabled)
        repo.setRcsFilter(sanitized.rcsFilterEnabled)
        repo.setPostCallScreen(sanitized.postCallScreenEnabled)
        repo.setSilentVoicemail(sanitized.silentVoicemailEnabled)
        repo.setPushAlert(sanitized.pushAlertEnabled)
        repo.resetPushAlertPackages()
        sanitized.pushAlertDisabledPackages.forEach { repo.togglePushAlertPackage(it, allowed = false) }
        repo.setAllowedRegions(sanitized.allowedRegions.toSet())
        repo.setRegionBlock(sanitized.regionBlockEnabled && sanitized.allowedRegions.isNotEmpty())
        repo.setCnapTrustPatterns(sanitized.cnapTrustPatterns.toSet())
        repo.setCnapBlockPatterns(sanitized.cnapBlockPatterns.toSet())
        repo.setActiveProfileName(sanitized.activeProfileName)
    }

    private val BackupLogEntry.conflictKey: String
        get() = logKey?.takeIf { it.isNotBlank() } ?: "$number|$timestamp|$isCall"

    private val BlockedCall.conflictKey: String
        get() = logKey?.takeIf { it.isNotBlank() } ?: "$number|$timestamp|$isCall"

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

            RestoreFailure.TOO_MANY_ITEMS -> {
                context.getString(R.string.backup_restore_too_many_items, MAX_BACKUP_RESTORE_ROWS)
            }

            RestoreFailure.NO_VALID_ITEMS -> {
                context.getString(R.string.backup_restore_no_valid_items)
            }
        }

    private fun normalizeImportedNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        val digits = filterAsciiDigits(trimmed)
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
