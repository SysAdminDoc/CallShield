package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.RestoreJournal
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
 *   and blocked-call/SMS logs.
 * - **v5**: preserves temporary block/allow expiry and the selected
 *   notification-screening apps.
 * - **v6**: preserves per-category call actions.
 * - **v7**: preserves the privacy-safe keys for selected contact-group scope.
 * - **v8**: includes stable block reason codes and deciding rule IDs in logs.
 * - **v9**: includes privacy-safe checker cutoff/error diagnostics in logs.
 *   The reader accepts v1-v9; the writer emits v9.
 *   Older backups that don't carry schedule fields are restored with
 *   all-zeros — the Kotlin defaults on [WildcardRule] and
 *   [SmsKeywordRule] treat that as "always active", preserving
 *   pre-v3 behavior.
 */
@Suppress("TooManyFunctions")
object BackupRestore {
    private const val MIN_IMPORTED_DIGITS = 5

    /** Length cap for a non-numeric log sender identity kept verbatim on restore. */
    private const val MAX_LOG_IDENTITY_LEN = 64
    private const val CURRENT_BACKUP_VERSION = 9
    private const val OLDEST_SUPPORTED_VERSION = 1
    internal const val MAX_BACKUP_RESTORE_ROWS = MAX_IMPORT_ROWS

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val backupSettingsAdapter = moshi.adapter(BackupSettings::class.java)

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
        val expiresAt: Long? = null,
    )

    data class BackupWhitelist(
        val number: String,
        val description: String,
        val isEmergency: Boolean = false,
        val expiresAt: Long? = null,
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
        val urlhausRemoteLookupEnabled: Boolean = false,
        val liveCallerEnrichmentEnabled: Boolean = false,
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
        val categoryCallActions: List<String> = emptyList(),
        val selectedContactGroups: List<String> = emptyList(),
        val outgoingRiskWarningEnabled: Boolean = false,
        val activeProfileName: String? = null,
        val notificationScreeningPackages: List<String>? = null,
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
        /** Stable wire value; matchReason remains only for backward-compatible round trips. */
        val reasonCode: String = "",
        val confidence: Int = 100,
        val logKey: String? = null,
        val ruleId: Long? = null,
        val pipelineDiagnostic: String? = null,
        val origid: String? = null,
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
                        // Export pulls only user-sourced rows, and restore always
                        // recreates them as source="user" (so sync's replaceBySource
                        // can't wipe them), so provenance isn't part of the schema.
                        BackupNumber(it.number, it.type, it.description, it.expiresAt)
                    }
                } else {
                    emptyList()
                }
            val whitelist =
                if (BackupSection.WHITELIST in sections) {
                    dao.getAllWhitelist().first().map {
                        BackupWhitelist(it.number, it.description, it.isEmergency, it.expiresAt)
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
                            reasonCode = it.reasonCode.wireValue,
                            confidence = it.confidence,
                            logKey = it.logKey,
                            ruleId = it.ruleId,
                            pipelineDiagnostic = it.pipelineDiagnostic,
                            origid = sanitizeOrigid(it.origid),
                        )
                    }
                } else {
                    emptyList()
                }

            // Keep the export within the same row cap that restore enforces, so a
            // large device never produces a backup the app then refuses to import.
            // The user-authored sections (numbers/whitelist/rules/settings) are
            // preserved in full; the blocked-call log — unbounded and re-generable
            // — absorbs the trim, keeping the most recent entries.
            val nonLogRows =
                numbers.size + whitelist.size + wildcards.size + keywords.size + ranges.size +
                    if (settings != null) 1 else 0
            val logBudget = (MAX_BACKUP_RESTORE_ROWS - nonLogRows).coerceAtLeast(0)
            val cappedLogs =
                if (logs.size > logBudget) {
                    logs.sortedByDescending { it.timestamp }.take(logBudget)
                } else {
                    logs
                }

            val backup =
                Backup(
                    blockedNumbers = numbers,
                    whitelistNumbers = whitelist,
                    wildcardRules = wildcards,
                    keywordRules = keywords,
                    rangeRules = ranges,
                    settings = settings,
                    logs = cappedLogs,
                )

            val adapter = moshi.adapter(Backup::class.java).indent("  ")
            adapter.toJson(backup)
        }

    suspend fun shareBackup(
        context: Context,
        sections: Set<BackupSection> = defaultExportSections,
        passphrase: CharArray? = null,
    ) {
        val json = createBackup(context, sections)
        val chooserIntent =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "backups")
                dir.mkdirs()
                // Timestamped name + prefix-scoped pruning (same pattern as
                // LogExporter): a fixed name would overwrite a prior backup a
                // share target may still be reading through the FileProvider
                // URI, and would collide when two backups run back to back.
                dir
                    .listFiles { file -> file.name.startsWith("callshield_backup_") }
                    ?.forEach { it.delete() }
                val encrypted = passphrase != null && passphrase.isNotEmpty()
                val timestamp = System.currentTimeMillis()
                val file = File(dir, if (encrypted) "callshield_backup_$timestamp.csbackup" else "callshield_backup_$timestamp.json")
                if (encrypted) {
                    file.writeBytes(
                        PortableBackupCrypto.encrypt(
                            json.toByteArray(Charsets.UTF_8),
                            requireNotNull(passphrase),
                        ),
                    )
                } else {
                    file.writeText(json)
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = if (encrypted) "application/octet-stream" else "application/json"
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
        passphrase: CharArray? = null,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            val previewResult = previewRestoreFromUri(context, uri, sections, passphrase)
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
        passphrase: CharArray? = null,
    ): RestorePreviewResult =
        withContext(Dispatchers.IO) {
            try {
                // Bounded read — a huge/hostile backup file is rejected rather
                // than materialized whole into memory before validation.
                val input =
                    context.contentResolver.openInputStream(uri)
                        ?: return@withContext RestorePreviewResult(
                            false,
                            context.getString(R.string.backup_restore_could_not_read),
                        )
                val bytes =
                    input.readBytesBounded(PortableBackupCrypto.maxEnvelopeBytes)
                        ?: return@withContext RestorePreviewResult(
                            false,
                            context.getString(R.string.backup_restore_file_too_large),
                        )
                val json =
                    when (val decrypted = PortableBackupCrypto.decrypt(bytes, passphrase)) {
                        PortableBackupCrypto.DecryptionResult.NotEncrypted -> {
                            if (passphrase != null && passphrase.isNotEmpty()) {
                                // The user typed a passphrase expecting an
                                // authenticated file. Silently restoring an
                                // unauthenticated plaintext one instead would
                                // be an integrity downgrade (a swapped file
                                // could inject whitelist entries unnoticed).
                                return@withContext RestorePreviewResult(
                                    false,
                                    context.getString(R.string.backup_restore_not_protected),
                                )
                            }
                            if (bytes.size.toLong() > MAX_IMPORT_FILE_BYTES) {
                                return@withContext RestorePreviewResult(
                                    false,
                                    context.getString(R.string.backup_restore_file_too_large),
                                )
                            }
                            bytes.toString(Charsets.UTF_8)
                        }

                        PortableBackupCrypto.DecryptionResult.PassphraseRequired -> {
                            return@withContext RestorePreviewResult(
                                false,
                                context.getString(R.string.backup_restore_passphrase_required),
                            )
                        }

                        is PortableBackupCrypto.DecryptionResult.Invalid -> {
                            return@withContext RestorePreviewResult(
                                false,
                                decrypted.message(context),
                            )
                        }

                        is PortableBackupCrypto.DecryptionResult.Success -> {
                            decrypted.plaintext.toString(Charsets.UTF_8)
                        }
                    }

                previewRestoreJson(context, json, AppDatabase.getInstance(context).spamDao(), sections)
            } catch (e: Exception) {
                // Don't surface the raw exception text (content URIs, SQLite
                // constraint names) to the UI — log it, show a localized reason.
                Log.w("BackupRestore", "Restore preview failed", e)
                RestorePreviewResult(false, context.getString(R.string.backup_restore_error_generic))
            }
        }

    private fun PortableBackupCrypto.DecryptionResult.Invalid.message(context: Context): String =
        when (reason) {
            PortableBackupCrypto.InvalidReason.TOO_LARGE -> {
                context.getString(R.string.backup_restore_file_too_large)
            }

            PortableBackupCrypto.InvalidReason.UNSUPPORTED_VERSION -> {
                context.getString(R.string.backup_restore_encryption_version_unsupported)
            }

            PortableBackupCrypto.InvalidReason.AUTHENTICATION_FAILED -> {
                context.getString(R.string.backup_restore_decryption_failed)
            }

            PortableBackupCrypto.InvalidReason.INVALID_FORMAT -> {
                context.getString(R.string.backup_restore_encrypted_invalid)
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

    /** Reconcile a restore interrupted between its DataStore and Room commits. */
    suspend fun reconcilePendingRestore(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).spamDao()
            reconcilePendingRestore(dao, SpamRepository.getInstance(context))
        }

    internal suspend fun reconcilePendingRestore(
        dao: SpamDao,
        repo: SpamRepository,
    ): Boolean {
        val journal = dao.getRestoreJournal() ?: return false
        val settingsJson =
            if (journal.phase == RestoreJournal.PHASE_ROOM_COMMITTED) {
                journal.desiredSettingsJson
            } else {
                journal.beforeSettingsJson
            }
        val settings = checkNotNull(backupSettingsAdapter.fromJson(settingsJson))
        settings.applyTo(repo)
        dao.deleteRestoreJournal()
        repo.invalidateRestoredRuleCaches()
        return true
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

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "LongParameterList",
        "NestedBlockDepth",
        "TooGenericExceptionCaught",
    )
    internal suspend fun restorePayload(
        context: Context,
        payload: RestorePayload,
        mode: RestoreMode,
        dao: SpamDao,
        repo: SpamRepository,
        selectedSections: Set<BackupSection> = defaultRestoreSections,
        settingsWriter: suspend (BackupSettings, SpamRepository) -> Unit = { settings, repository ->
            settings.applyTo(repository)
        },
    ): RestoreResult =
        try {
            var numbersRestored = 0
            var whitelistRestored = 0
            var rulesRestored = 0
            var keywordsRestored = 0
            var rangesRestored = 0
            var logsRestored = 0
            var settingsRestored = 0

            // The singleton Room journal turns the DataStore + Room restore
            // into a recoverable two-phase commit. Startup rolls settings back
            // while PREPARED, or reapplies the desired settings after the Room
            // transaction atomically advances the marker to ROOM_COMMITTED.
            val desiredSettings = payload.settings?.sanitized()
            val settingsBeforeRestore = desiredSettings?.let { repo.readPrefsSnapshot().toBackupSettings() }
            if (desiredSettings != null && settingsBeforeRestore != null) {
                dao.upsertRestoreJournal(
                    RestoreJournal(
                        phase = RestoreJournal.PHASE_PREPARED,
                        beforeSettingsJson = backupSettingsAdapter.toJson(settingsBeforeRestore),
                        desiredSettingsJson = backupSettingsAdapter.toJson(desiredSettings),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                try {
                    settingsWriter(desiredSettings, repo)
                    settingsRestored = 1
                } catch (settingsFailure: Exception) {
                    rollbackPreparedRestore(settingsBeforeRestore, repo, dao, settingsFailure)
                    throw settingsFailure
                }
            }

            try {
                // Atomic within Room: in REPLACE mode the clear and every
                // re-insert either commit together or roll back together.
                repo.runInTransaction {
                    if (mode == RestoreMode.REPLACE) {
                        clearSelectedBackupSections(dao, selectedSections)
                    }

                    for (n in payload.blockedNumbers) {
                        val applied =
                            if (n.expiresAt != null) {
                                repo.temporaryBlockNumber(n.number, n.expiresAt, n.type, n.description)
                            } else {
                                repo.blockNumber(n.number, n.type, n.description)
                            }
                        // A temp block refused by a local permanent allow (and
                        // vice versa below) must not inflate the success toast.
                        if (applied) numbersRestored++
                    }

                    for (w in payload.whitelistNumbers) {
                        val applied =
                            repo.addToWhitelist(
                                number = w.number,
                                description = w.description,
                                isEmergency = w.isEmergency,
                                expiresAt = w.expiresAt,
                            )
                        if (applied) whitelistRestored++
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

                    // Rows without a logKey escape the unique-index dedupe
                    // (SQLite unique indexes treat NULLs as distinct), so
                    // restoring the same pre-logKey backup twice would
                    // duplicate every legacy log row. Skip fallback-key
                    // matches, mirroring the preview's conflict counting;
                    // keyed rows keep REPLACE semantics via the index.
                    val existingLogKeys =
                        if (payload.logs.any { it.logKey.isNullOrBlank() }) {
                            dao.getBlockedCallConflictKeysSync().toHashSet()
                        } else {
                            emptySet()
                        }
                    for (log in payload.logs) {
                        if (log.logKey.isNullOrBlank() && log.conflictKey in existingLogKeys) continue
                        dao.insertBlockedCall(
                            BlockedCall(
                                number = log.number,
                                timestamp = log.timestamp,
                                type = log.type,
                                wasBlocked = log.wasBlocked,
                                isCall = log.isCall,
                                smsBody = log.smsBody,
                                matchReason = log.matchReason,
                                reasonCode = BlockReasonCode.fromStored(log.reasonCode.ifBlank { log.matchReason }),
                                confidence = log.confidence,
                                logKey = log.logKey,
                                ruleId = log.ruleId,
                                pipelineDiagnostic = log.pipelineDiagnostic,
                                origid = sanitizeOrigid(log.origid),
                            ),
                        )
                        logsRestored++
                    }

                    if (desiredSettings != null) {
                        dao.updateRestoreJournalPhase(RestoreJournal.PHASE_ROOM_COMMITTED)
                    }
                }
            } catch (roomFailure: Exception) {
                settingsBeforeRestore?.let { settings ->
                    rollbackPreparedRestore(settings, repo, dao, roomFailure)
                }
                throw roomFailure
            }

            if (desiredSettings != null) dao.deleteRestoreJournal()

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
            Log.w("BackupRestore", "Restore failed", e)
            RestoreResult(false, context.getString(R.string.backup_restore_error_generic))
        }

    private suspend fun rollbackPreparedRestore(
        settingsBeforeRestore: BackupSettings,
        repo: SpamRepository,
        dao: SpamDao,
        failure: Exception,
    ) {
        runCatching {
            settingsBeforeRestore.applyTo(repo)
            dao.deleteRestoreJournal()
        }.exceptionOrNull()?.let(failure::addSuppressed)
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

    private fun Backup.toRestorePayload(
        sections: Set<BackupSection>,
    ): RestorePayload = toRestorePayload(sections, System.currentTimeMillis())

    private fun Backup.toRestorePayload(
        sections: Set<BackupSection>,
        nowMillis: Long,
    ): RestorePayload =
        RestorePayload(
            blockedNumbers =
                if (BackupSection.BLOCKED_NUMBERS in sections) {
                    blockedNumbers.mapNotNull { it.sanitized(nowMillis) }
                } else {
                    emptyList()
                },
            whitelistNumbers =
                if (BackupSection.WHITELIST in sections) {
                    whitelistNumbers.mapNotNull { it.sanitized(nowMillis) }
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
                        val normalizedNumber = normalizeImportedLogIdentity(log.number) ?: return@mapNotNull null
                        BackupLogEntry(
                            number = normalizedNumber,
                            timestamp = log.timestamp.coerceAtLeast(0L),
                            type = log.type.trim().ifBlank { "unknown" },
                            wasBlocked = log.wasBlocked,
                            isCall = log.isCall,
                            smsBody = log.smsBody,
                            matchReason = log.matchReason.trim(),
                            reasonCode = BlockReasonCode.fromStored(log.reasonCode.ifBlank { log.matchReason }).wireValue,
                            confidence = log.confidence.coerceIn(0, 100),
                            logKey = log.logKey?.trim()?.ifBlank { null },
                            ruleId = log.ruleId,
                            pipelineDiagnostic =
                                log.pipelineDiagnostic
                                    ?.trim()
                                    ?.take(512)
                                    ?.ifBlank { null },
                            origid = sanitizeOrigid(log.origid),
                        )
                    }
                } else {
                    emptyList()
                },
        )

    private fun BackupNumber.sanitized(nowMillis: Long): BackupNumber? {
        val normalizedNumber =
            if (expiresAt == null || expiresAt > nowMillis) normalizeImportedNumber(number) else null
        return normalizedNumber?.let {
            copy(
                number = it,
                type = type.trim().ifBlank { "unknown" },
                description = description.trim(),
            )
        }
    }

    private fun BackupWhitelist.sanitized(nowMillis: Long): BackupWhitelist? {
        val normalizedNumber =
            if (expiresAt == null || expiresAt > nowMillis) normalizeImportedNumber(number) else null
        return normalizedNumber?.let {
            copy(
                number = it,
                description = description.trim(),
            )
        }
    }

    private suspend fun countConflicts(
        dao: SpamDao,
        payload: RestorePayload,
    ): RestoreCounts {
        // Only read each existing table when the payload actually has rows for
        // it — a settings-only restore preview must not query anything. Logs use
        // the conflict-key projection so restore never materializes full rows
        // (which carry SMS bodies) on a heavy-spam device.
        val existingBlocks =
            if (payload.blockedNumbers.isEmpty()) emptySet() else dao.getUserBlockedNumbersSync().map { it.number }.toSet()
        val existingWhitelist =
            if (payload.whitelistNumbers.isEmpty()) {
                emptySet()
            } else {
                dao
                    .getAllWhitelist()
                    .first()
                    .map { it.number }
                    .toSet()
            }
        val existingWildcards =
            if (payload.wildcardRules.isEmpty()) {
                emptySet()
            } else {
                dao
                    .getAllWildcardRules()
                    .first()
                    .map { it.pattern to it.isRegex }
                    .toSet()
            }
        val existingKeywords =
            if (payload.keywordRules.isEmpty()) {
                emptySet()
            } else {
                dao
                    .getAllKeywordRules()
                    .first()
                    .map { it.keyword to it.caseSensitive }
                    .toSet()
            }
        val existingRanges =
            if (payload.rangeRules.isEmpty()) {
                emptySet()
            } else {
                dao
                    .getAllHashWildcardRules()
                    .first()
                    .map { it.pattern }
                    .toSet()
            }
        val existingLogs =
            if (payload.logs.isEmpty()) emptySet() else dao.getBlockedCallConflictKeysSync().toSet()

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

    @Suppress("LongMethod")
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
            urlhausRemoteLookupEnabled = this[SpamRepository.KEY_URLHAUS_REMOTE_LOOKUP] ?: false,
            liveCallerEnrichmentEnabled = this[SpamRepository.KEY_LIVE_CALLER_ENRICHMENT] ?: false,
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
            categoryCallActions =
                CategoryCallPolicy.sanitize(this[SpamRepository.KEY_CATEGORY_CALL_ACTIONS].orEmpty()).sorted(),
            selectedContactGroups =
                ContactGroupCatalog
                    .preserveScope(this[SpamRepository.KEY_SELECTED_CONTACT_GROUPS].orEmpty())
                    .sorted(),
            outgoingRiskWarningEnabled = this[SpamRepository.KEY_OUTGOING_RISK_WARNING] ?: false,
            activeProfileName = this[SpamRepository.KEY_ACTIVE_PROFILE],
            // Preserve "never customized" as null: resolving the default
            // set into a concrete list here (and writing it back on
            // restore) permanently pinned the defaults, so sources added
            // to the catalog later never auto-enabled for restored users.
            // Mirrors the pushAlertDisabledPackages empty->remove pattern.
            notificationScreeningPackages =
                this[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES]
                    ?.let { NotificationScreeningSources.enabledPackages(it).sorted() },
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
            categoryCallActions = CategoryCallPolicy.sanitize(categoryCallActions).sorted(),
            selectedContactGroups = ContactGroupCatalog.preserveScope(selectedContactGroups).sorted(),
            activeProfileName = activeProfileName?.trim()?.ifBlank { null },
            notificationScreeningPackages =
                notificationScreeningPackages
                    ?.map { it.trim() }
                    ?.filter { NotificationScreeningSources.sourceFor(it) != null }
                    ?.distinct()
                    ?.sorted(),
        )

    @Suppress("LongMethod")
    private suspend fun BackupSettings.applyTo(repo: SpamRepository) {
        val sanitized = sanitized()
        repo.editPreferences { preferences ->
            sanitized.writeTo(preferences)
        }
    }

    @Suppress("LongMethod")
    private fun BackupSettings.writeTo(preferences: MutablePreferences) {
        preferences[SpamRepository.KEY_BLOCK_CALLS] = blockCallsEnabled
        preferences[SpamRepository.KEY_BLOCK_SMS] = blockSmsEnabled
        preferences[SpamRepository.KEY_BLOCK_UNKNOWN] = blockUnknownEnabled
        preferences[SpamRepository.KEY_STIR_SHAKEN] = stirShakenEnabled
        preferences[SpamRepository.KEY_STIR_TRUSTED_ALLOW] = stirTrustedAllowEnabled
        preferences[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] = autoMuteLowConfidenceEnabled
        preferences[SpamRepository.KEY_NEIGHBOR_SPOOF] = neighborSpoofEnabled
        preferences[SpamRepository.KEY_HEURISTICS] = heuristicsEnabled
        preferences[SpamRepository.KEY_SMS_CONTENT] = smsContentEnabled
        preferences[SpamRepository.KEY_SMS_BURST] = smsBurstEnabled
        preferences[SpamRepository.KEY_URLHAUS_STRIP_QUERY] = urlhausStripQueryEnabled
        preferences[SpamRepository.KEY_URLHAUS_REMOTE_LOOKUP] = urlhausRemoteLookupEnabled
        preferences[SpamRepository.KEY_LIVE_CALLER_ENRICHMENT] = liveCallerEnrichmentEnabled
        preferences[SpamRepository.KEY_CONTACT_WHITELIST] = contactWhitelistEnabled
        preferences[SpamRepository.KEY_CONTACTS_ONLY] = contactsOnlyEnabled
        preferences[SpamRepository.KEY_OUTGOING_RISK_WARNING] = outgoingRiskWarningEnabled
        preferences[SpamRepository.KEY_DB_PREFIX_EXPANSION] = dbPrefixExpansionEnabled
        preferences[SpamRepository.KEY_AGGRESSIVE_MODE] = aggressiveModeEnabled
        preferences[SpamRepository.KEY_ANSWERED_CALLER_TRUST] = answeredCallerTrustEnabled
        preferences[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD] = answeredCallerThreshold
        preferences[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS] = answeredCallerWindowDays
        preferences[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] = emergencyCallbackGraceEnabled
        preferences[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES] = emergencyCallbackWindowMinutes
        preferences[SpamRepository.KEY_TIME_BLOCK] = timeBlockEnabled
        preferences[SpamRepository.KEY_TIME_BLOCK_START] = timeBlockStartHour
        preferences[SpamRepository.KEY_TIME_BLOCK_END] = timeBlockEndHour
        preferences[SpamRepository.KEY_FREQ_ESCALATION] = frequencyEscalationEnabled
        preferences[SpamRepository.KEY_FREQ_THRESHOLD] = frequencyThreshold
        preferences[SpamRepository.KEY_AUTO_CLEANUP] = autoCleanupEnabled
        preferences[SpamRepository.KEY_CLEANUP_DAYS] = cleanupDays
        preferences[SpamRepository.KEY_ML_SCORER] = mlScorerEnabled
        preferences[SpamRepository.KEY_RCS_FILTER] = rcsFilterEnabled
        preferences[SpamRepository.KEY_POST_CALL_SCREEN] = postCallScreenEnabled
        preferences[SpamRepository.KEY_SILENT_VOICEMAIL] = silentVoicemailEnabled
        preferences[SpamRepository.KEY_PUSH_ALERT] = pushAlertEnabled
        if (pushAlertDisabledPackages.isEmpty()) {
            preferences.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED)
        } else {
            preferences[SpamRepository.KEY_PUSH_ALERT_DISABLED] = pushAlertDisabledPackages.toSet()
        }
        if (allowedRegions.isEmpty()) {
            preferences.remove(SpamRepository.KEY_ALLOWED_REGIONS)
        } else {
            preferences[SpamRepository.KEY_ALLOWED_REGIONS] = allowedRegions.toSet()
        }
        preferences[SpamRepository.KEY_REGION_BLOCK] = regionBlockEnabled && allowedRegions.isNotEmpty()
        if (cnapTrustPatterns.isEmpty()) {
            preferences.remove(SpamRepository.KEY_CNAP_TRUST_PATTERNS)
        } else {
            preferences[SpamRepository.KEY_CNAP_TRUST_PATTERNS] = cnapTrustPatterns.toSet()
        }
        if (cnapBlockPatterns.isEmpty()) {
            preferences.remove(SpamRepository.KEY_CNAP_BLOCK_PATTERNS)
        } else {
            preferences[SpamRepository.KEY_CNAP_BLOCK_PATTERNS] = cnapBlockPatterns.toSet()
        }
        if (categoryCallActions.isEmpty()) {
            preferences.remove(SpamRepository.KEY_CATEGORY_CALL_ACTIONS)
        } else {
            preferences[SpamRepository.KEY_CATEGORY_CALL_ACTIONS] = categoryCallActions.toSet()
        }
        if (selectedContactGroups.isEmpty()) {
            preferences.remove(SpamRepository.KEY_SELECTED_CONTACT_GROUPS)
        } else {
            preferences[SpamRepository.KEY_SELECTED_CONTACT_GROUPS] = selectedContactGroups.toSet()
        }
        if (activeProfileName == null) {
            preferences.remove(SpamRepository.KEY_ACTIVE_PROFILE)
        } else {
            preferences[SpamRepository.KEY_ACTIVE_PROFILE] = activeProfileName
        }
        if (notificationScreeningPackages == null) {
            // Backup was taken with the follow-the-defaults sentinel (or is a
            // pre-v7 backup without the field): restore that state rather
            // than leaving whatever customization the device currently has.
            preferences.remove(SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES)
        } else {
            preferences[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES] =
                notificationScreeningPackages.toSet()
        }
    }

    private val BackupLogEntry.conflictKey: String
        get() = logKey?.takeIf { it.isNotBlank() } ?: "$number|$timestamp|$isCall"

    private val BlockedCall.conflictKey: String
        get() = logKey?.takeIf { it.isNotBlank() } ?: "$number|$timestamp|$isCall"

    private fun sanitizeOrigid(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return runCatching { UUID.fromString(trimmed) }
            .getOrNull()
            ?.toString()
            ?.takeIf { it.equals(trimmed, ignoreCase = true) }
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
     * Log-row sender identity. Unlike [normalizeImportedNumber], accepts a
     * non-numeric alphanumeric sender ID (e.g. "BANK-ALERT") or a hashed opaque
     * sender — these are legitimate `call_log.number` values that a phone-number
     * normalizer would drop, silently losing SMS history on a backup round-trip.
     * Length-capped so a hostile backup can't inject an oversized string;
     * malformed pure-digit strings still fall through to null.
     */
    private fun normalizeImportedLogIdentity(rawNumber: String): String? {
        normalizeImportedNumber(rawNumber)?.let { return it }
        val trimmed = rawNumber.trim()
        val hasNonDigit = trimmed.any { it !in '0'..'9' && it != '+' }
        return if (trimmed.isNotBlank() && hasNonDigit) trimmed.take(MAX_LOG_IDENTITY_LEN) else null
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
