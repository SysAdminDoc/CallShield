package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sysadmindoc.callshield.data.checker.BlockResult
import com.sysadmindoc.callshield.data.checker.CheckContext
import com.sysadmindoc.callshield.data.checker.CheckerPipeline
import com.sysadmindoc.callshield.data.checker.IChecker
import com.sysadmindoc.callshield.data.checker.SpamCheckers
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.*
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "callshield_prefs")

class SpamRepository(private val context: Context) {
    private val dao: SpamDao = AppDatabase.getInstance(context).spamDao()
    private val remote = GitHubDataSource()
    private val dataStore = context.dataStore
    private val syncMutex = Mutex()

    // ── Hot-path caches ──────────────────────────────────────────────
    // isSpam() is the critical real-time path (must complete within the
    // CallScreeningService 5-second deadline). Loading all prefixes,
    // wildcard rules, and keyword rules from Room on every call added
    // unnecessary I/O latency. These caches are invalidated on writes.
    @Volatile private var cachedPrefixes: List<SpamPrefix>? = null
    @Volatile private var cachedWildcardRules: List<WildcardRule>? = null
    @Volatile private var cachedKeywordRules: List<SmsKeywordRule>? = null
    @Volatile private var cachedHashWildcardRules: List<HashWildcardRule>? = null

    private suspend fun getPrefixes(): List<SpamPrefix> {
        return cachedPrefixes ?: dao.getAllPrefixes().also { cachedPrefixes = it }
    }
    private suspend fun getActiveWildcards(): List<WildcardRule> {
        return cachedWildcardRules ?: dao.getActiveWildcardRules().also { cachedWildcardRules = it }
    }
    private suspend fun getActiveKeywords(): List<SmsKeywordRule> {
        return cachedKeywordRules ?: dao.getActiveKeywordRules().also { cachedKeywordRules = it }
    }
    private suspend fun getActiveHashWildcards(): List<HashWildcardRule> {
        return cachedHashWildcardRules ?: dao.getActiveHashWildcardRules().also { cachedHashWildcardRules = it }
    }
    private fun invalidatePrefixCache() { cachedPrefixes = null }
    private fun invalidateWildcardCache() { cachedWildcardRules = null }
    private fun invalidateKeywordCache() { cachedKeywordRules = null }
    private fun invalidateHashWildcardCache() { cachedHashWildcardRules = null }
    private fun invalidateAllCaches() {
        cachedPrefixes = null
        cachedWildcardRules = null
        cachedKeywordRules = null
        cachedHashWildcardRules = null
    }

    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        private val KEY_LAST_SYNC_SOURCE = stringPreferencesKey("last_sync_source")
        private val KEY_LAST_SHA = stringPreferencesKey("last_data_sha")
        private val KEY_DB_VERSION = intPreferencesKey("db_version")
        val KEY_BLOCK_CALLS = booleanPreferencesKey("block_calls_enabled")
        val KEY_BLOCK_SMS = booleanPreferencesKey("block_sms_enabled")
        val KEY_BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown_enabled")
        val KEY_STIR_SHAKEN = booleanPreferencesKey("stir_shaken_enabled")
        // STIR/SHAKEN attestation-level TRUST allow. When enabled, a
        // carrier-verified PASS (attestation A/B equivalent) short-circuits
        // the weaker downstream blockers (heuristic, ML, campaign-burst,
        // frequency) — the carrier explicitly signed for this caller's
        // number so we trust it above statistical signals, but NOT above
        // explicit user blocklist entries (those still sit higher in
        // priority order). Defaulted on: the FP-fighting value is large
        // and the data is carrier-signed, not self-asserted.
        val KEY_STIR_TRUSTED_ALLOW = booleanPreferencesKey("stir_trusted_allow_enabled")
        // Auto-mute mode. When enabled, blocks with confidence < 60 (weaker
        // heuristic/ML hits) are silenced via setSilenceCall() instead of
        // hard-rejected — the call reaches voicemail with no ring, and the
        // user can inspect the entry later. Off by default because the
        // current hard-reject matches most users' expectations. When
        // KEY_SILENT_VOICEMAIL is already on, that user preference wins
        // (silence-everything beats silence-only-uncertain).
        val KEY_AUTOMUTE_LOW_CONFIDENCE = booleanPreferencesKey("automute_low_confidence_enabled")
        val KEY_NEIGHBOR_SPOOF = booleanPreferencesKey("neighbor_spoof_enabled")
        val KEY_HEURISTICS = booleanPreferencesKey("heuristics_enabled")
        val KEY_SMS_CONTENT = booleanPreferencesKey("sms_content_analysis_enabled")
        val KEY_CONTACT_WHITELIST = booleanPreferencesKey("contact_whitelist_enabled")
        val KEY_AGGRESSIVE_MODE = booleanPreferencesKey("aggressive_mode_enabled")
        // Feature 9: Time-based blocking
        val KEY_TIME_BLOCK = booleanPreferencesKey("time_block_enabled")
        val KEY_TIME_BLOCK_START = intPreferencesKey("time_block_start_hour") // 0-23
        val KEY_TIME_BLOCK_END = intPreferencesKey("time_block_end_hour")
        // Feature 10: Frequency auto-escalation
        val KEY_FREQ_ESCALATION = booleanPreferencesKey("freq_escalation_enabled")
        val KEY_FREQ_THRESHOLD = intPreferencesKey("freq_threshold")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup_enabled")
        private val KEY_CLEANUP_DAYS = intPreferencesKey("cleanup_retention_days")
        private val KEY_ABSTRACT_API_KEY = stringPreferencesKey("abstract_api_key")
        val KEY_ML_SCORER = booleanPreferencesKey("ml_scorer_enabled")
        val KEY_RCS_FILTER = booleanPreferencesKey("rcs_filter_enabled")
        // Silent voicemail mode: when enabled, blocked calls are silenced (no
        // ring) and routed to voicemail instead of hard-rejected. Less
        // disruptive — phone stays quiet, caller hears normal rings and
        // reaches voicemail, user can review later without the interruption
        // or the missed-call entry from a rejection.
        val KEY_SILENT_VOICEMAIL = booleanPreferencesKey("silent_voicemail_mode")
        // A3 push-alert bridge — master toggle. When off, the registry is
        // not fed by RcsNotificationListener and PushAlertChecker returns
        // null unconditionally, so the pipeline behaves as if the feature
        // didn't exist. Default on — the bridge is the single biggest
        // false-positive fix and opt-in adoption would waste it.
        val KEY_PUSH_ALERT = booleanPreferencesKey("push_alert_enabled")
        // A3 source allowlist — opt-out semantics. The hardcoded default
        // set lives in [PushAlertRegistry.ALERT_SOURCE_PACKAGES]; this
        // StringSet records packages the user has turned OFF. An empty /
        // missing preference means "use the full default set", so future
        // additions to the default list propagate to existing users
        // without them re-enabling anything.
        val KEY_PUSH_ALERT_DISABLED = stringSetPreferencesKey("push_alert_disabled_packages")

        const val SYNC_SOURCE_REMOTE = "remote"
        const val SYNC_SOURCE_BUNDLED = "bundled"

        @Volatile
        private var INSTANCE: SpamRepository? = null

        fun getInstance(context: Context): SpamRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpamRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Settings
    val blockCallsEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BLOCK_CALLS] ?: true }
    val blockSmsEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BLOCK_SMS] ?: true }
    val blockUnknownEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BLOCK_UNKNOWN] ?: false }
    val stirShakenEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_STIR_SHAKEN] ?: true }
    val stirTrustedAllowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_STIR_TRUSTED_ALLOW] ?: true }
    val autoMuteLowConfidenceEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false }
    val neighborSpoofEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_NEIGHBOR_SPOOF] ?: true }
    val heuristicsEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_HEURISTICS] ?: true }
    val smsContentEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SMS_CONTENT] ?: true }
    val contactWhitelistEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_CONTACT_WHITELIST] ?: true }
    val aggressiveModeEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_AGGRESSIVE_MODE] ?: false }
    val timeBlockEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_TIME_BLOCK] ?: false }
    val timeBlockStart: Flow<Int> = dataStore.data.map { it[KEY_TIME_BLOCK_START] ?: 22 }
    val timeBlockEnd: Flow<Int> = dataStore.data.map { it[KEY_TIME_BLOCK_END] ?: 7 }
    val freqEscalationEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_FREQ_ESCALATION] ?: true }
    val freqThreshold: Flow<Int> = dataStore.data.map { it[KEY_FREQ_THRESHOLD] ?: 3 }
    val onboardingDone: Flow<Boolean> = dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }
    val autoCleanupEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_AUTO_CLEANUP] ?: false }
    val cleanupDays: Flow<Int> = dataStore.data.map { it[KEY_CLEANUP_DAYS] ?: 30 }
    // Optional AbstractAPI key for carrier/number-type enrichment in the Caller ID overlay.
    // Never used in the blocking pipeline — blocking stays 100% local/offline.
    val abstractApiKey: Flow<String> = dataStore.data.map { it[KEY_ABSTRACT_API_KEY] ?: "" }
    suspend fun setAbstractApiKey(key: String) = dataStore.edit { it[KEY_ABSTRACT_API_KEY] = key }

    val mlScorerEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ML_SCORER] ?: true }
    val rcsFilterEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_RCS_FILTER] ?: true }
    /** Off by default — some users want the missed-call log entry to confirm they were targeted. */
    val silentVoicemailEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SILENT_VOICEMAIL] ?: false }
    val pushAlertEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_PUSH_ALERT] ?: true }
    /** Empty set is the default — means "no packages have been opted out". */
    val pushAlertDisabledPackages: Flow<Set<String>> =
        dataStore.data.map { it[KEY_PUSH_ALERT_DISABLED] ?: emptySet() }
    suspend fun setMlScorer(enabled: Boolean) = dataStore.edit { it[KEY_ML_SCORER] = enabled }
    suspend fun setRcsFilter(enabled: Boolean) = dataStore.edit { it[KEY_RCS_FILTER] = enabled }
    suspend fun setSilentVoicemail(enabled: Boolean) = dataStore.edit { it[KEY_SILENT_VOICEMAIL] = enabled }
    suspend fun setPushAlert(enabled: Boolean) = dataStore.edit { it[KEY_PUSH_ALERT] = enabled }

    /**
     * Flip a single package between allowed and opted-out. Mutates the
     * persisted [KEY_PUSH_ALERT_DISABLED] set — an empty result clears
     * the key so the "use default" flow keeps working.
     */
    suspend fun togglePushAlertPackage(pkg: String, allowed: Boolean) = dataStore.edit { prefs ->
        val current = prefs[KEY_PUSH_ALERT_DISABLED] ?: emptySet()
        val next = if (allowed) current - pkg else current + pkg
        if (next.isEmpty()) prefs.remove(KEY_PUSH_ALERT_DISABLED)
        else prefs[KEY_PUSH_ALERT_DISABLED] = next
    }

    /** Restore the default allowlist by removing every opt-out. */
    suspend fun resetPushAlertPackages() = dataStore.edit { it.remove(KEY_PUSH_ALERT_DISABLED) }

    suspend fun setOnboardingDone() = dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    suspend fun setAutoCleanup(enabled: Boolean) = dataStore.edit { it[KEY_AUTO_CLEANUP] = enabled }
    suspend fun setCleanupDays(days: Int) = dataStore.edit { it[KEY_CLEANUP_DAYS] = days }

    // Last sync timestamp for freshness indicator
    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }
    val lastSyncSource: Flow<String> = dataStore.data.map { it[KEY_LAST_SYNC_SOURCE] ?: "" }
    suspend fun setBlockCalls(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_CALLS] = enabled }
    suspend fun setBlockSms(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_SMS] = enabled }
    suspend fun setBlockUnknown(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_UNKNOWN] = enabled }
    suspend fun setStirShaken(enabled: Boolean) = dataStore.edit { it[KEY_STIR_SHAKEN] = enabled }
    suspend fun setStirTrustedAllow(enabled: Boolean) = dataStore.edit { it[KEY_STIR_TRUSTED_ALLOW] = enabled }
    suspend fun setAutoMuteLowConfidence(enabled: Boolean) = dataStore.edit { it[KEY_AUTOMUTE_LOW_CONFIDENCE] = enabled }
    suspend fun setNeighborSpoof(enabled: Boolean) = dataStore.edit { it[KEY_NEIGHBOR_SPOOF] = enabled }
    suspend fun setHeuristics(enabled: Boolean) = dataStore.edit { it[KEY_HEURISTICS] = enabled }
    suspend fun setSmsContent(enabled: Boolean) = dataStore.edit { it[KEY_SMS_CONTENT] = enabled }
    suspend fun setContactWhitelist(enabled: Boolean) = dataStore.edit { it[KEY_CONTACT_WHITELIST] = enabled }
    suspend fun setAggressiveMode(enabled: Boolean) = dataStore.edit { it[KEY_AGGRESSIVE_MODE] = enabled }
    suspend fun setTimeBlock(enabled: Boolean) = dataStore.edit { it[KEY_TIME_BLOCK] = enabled }
    suspend fun setTimeBlockStart(hour: Int) = dataStore.edit { it[KEY_TIME_BLOCK_START] = hour }
    suspend fun setTimeBlockEnd(hour: Int) = dataStore.edit { it[KEY_TIME_BLOCK_END] = hour }
    suspend fun setFreqEscalation(enabled: Boolean) = dataStore.edit { it[KEY_FREQ_ESCALATION] = enabled }

    /**
     * Read the full preferences snapshot once. Use this from hot paths
     * (CallScreeningService, SmsReceiver) that need several settings at
     * once — calling [Flow.first] per key regresses the 5-second deadline.
     */
    suspend fun readPrefsSnapshot(): Preferences = dataStore.data.first()

    // ── Internal accessors for the checker pipeline ────────────────────
    //
    // Each checker lives in `data/checker` and needs narrow access to the
    // DAO and the hot-path caches without exposing the full DAO surface
    // to the rest of the app. These are `internal` (same Gradle module)
    // and prefixed with `*Internal` to signal "not for general use".

    internal suspend fun findWhitelistEntryInternal(normalized: String): WhitelistEntry? =
        dao.findWhitelistEntry(normalized)

    internal suspend fun findByNumberInternal(normalized: String): SpamNumber? =
        dao.findByNumber(normalized)

    internal suspend fun getPrefixesCachedInternal(): List<SpamPrefix> = getPrefixes()

    internal suspend fun getActiveWildcardsCachedInternal(): List<WildcardRule> = getActiveWildcards()

    internal suspend fun getActiveKeywordsCachedInternal(): List<SmsKeywordRule> = getActiveKeywords()

    internal suspend fun getActiveHashWildcardsCachedInternal(): List<HashWildcardRule> = getActiveHashWildcards()

    internal suspend fun getNumberFrequencySinceInternal(number: String, since: Long): Int =
        dao.getNumberFrequencySince(number, since)

    internal suspend fun getRecentBlockedNumbersInternal(since: Long): List<BlockedCall> =
        dao.getRecentBlockedNumbers(since)

    // ── Checker pipeline ───────────────────────────────────────────────
    //
    // Built lazily once per process (not per call) so the hot path doesn't
    // pay the construction cost. Pre-sorted by priority descending.

    private val callChain: List<IChecker> by lazy { SpamCheckers.buildCallChain(this, context) }
    private val smsExtensions: List<IChecker> by lazy { SpamCheckers.buildSmsExtensions(this, context) }

    // ── Primary spam check ─────────────────────────────────────────────
    /**
     * @param realtimeCall `true` for live incoming calls/SMS (the default) —
     *   feeds `CampaignDetector` and may surface the suspicious-caller overlay.
     *   Pass `false` from the historical call-log / SMS-inbox scanners so they
     *   don't poison the in-memory campaign detector with old numbers (any 5+
     *   historical unknowns sharing an NPA-NXX would otherwise flag that prefix
     *   as an active campaign for the next hour) and don't pop overlays for
     *   calls that already happened.
     * @param prefsSnapshot caller-supplied prefs read. Pass a pre-loaded
     *   snapshot to avoid repeating the DataStore read when the caller has
     *   already taken one (e.g. CallShieldScreeningService).
     */
    suspend fun isSpam(
        number: String,
        smsBody: String? = null,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
        verificationStatus: Int? = null,
    ): SpamCheckResult {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return SpamCheckResult(false)

        val prefs = prefsSnapshot ?: dataStore.data.first()
        val ctx = CheckContext(
            appContext = context,
            number = normalized,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefs = prefs,
            verificationStatus = verificationStatus,
        )

        // Priority-sorted pipeline — every detection layer is an IChecker.
        // First non-null result wins. See data/checker/ for the 13 layers.
        val verdict = CheckerPipeline.run(callChain, ctx)
            ?: return SpamCheckResult(false)

        return verdict.toSpamCheckResult()
    }

    // ── SMS-specific check ─────────────────────────────────────────────
    /** @param realtimeCall see [isSpam] — pass `false` from historical scanners. */
    suspend fun isSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult {
        // Shared number-based chain runs first. Only a positive spam
        // verdict short-circuits — an allow-result (e.g. contact whitelist)
        // lets SMS-specific layers still inspect the body, preserving the
        // pre-refactor behavior where a contact's message could still be
        // caught by keyword or content rules the user explicitly set.
        val prefs = prefsSnapshot ?: dataStore.data.first()
        val numberResult = isSpam(number, smsBody = body, realtimeCall = realtimeCall, prefsSnapshot = prefs)
        if (numberResult.isSpam) return numberResult

        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return SpamCheckResult(false)
        val ctx = CheckContext(
            appContext = context,
            number = normalized,
            smsBody = body,
            realtimeCall = realtimeCall,
            prefs = prefs,
        )
        val verdict = CheckerPipeline.run(smsExtensions, ctx)
            ?: return SpamCheckResult(false)

        return verdict.toSpamCheckResult()
    }

    // ── Sync ───────────────────────────────────────────────────────────
    /**
     * @param force When true, skips the SHA check and always downloads.
     *              Used for manual sync to guarantee fresh data.
     */
    suspend fun syncFromGitHub(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val currentCount = dao.getSpamCount()
                if (!force) {
                    val currentSha = dataStore.data.first()[KEY_LAST_SHA]
                    val remoteResult = remote.checkForUpdate()
                    val newSha = remoteResult.getOrNull()

                    if (newSha != null && newSha == currentSha) {
                        return@withContext SyncResult(success = true, message = "Database is up to date")
                    }
                }

                val result = remote.fetchSpamDatabase()
                if (result.isSuccess) {
                    val database = result.getOrThrow()
                    val newSha = remote.checkForUpdate().getOrNull()
                    val (numberCount, prefixCount) = persistSpamDatabase(
                        database = database,
                        sha = newSha,
                        syncSource = SYNC_SOURCE_REMOTE
                    )
                    return@withContext SyncResult(
                        success = true,
                        message = "Synced $numberCount numbers, $prefixCount prefixes"
                    )
                }

                val remoteError = result.exceptionOrNull()?.message ?: "Unknown sync error"
                if (currentCount > 0) {
                    return@withContext SyncResult(
                        success = true,
                        message = "GitHub sync unavailable ($remoteError). Your existing spam database is still active.",
                        warning = true
                    )
                }

                val bundledDatabase = loadBundledSpamDatabase()
                if (bundledDatabase.isSuccess) {
                    val database = bundledDatabase.getOrThrow()
                    val (numberCount, prefixCount) = persistSpamDatabase(
                        database = database,
                        sha = null,
                        syncSource = SYNC_SOURCE_BUNDLED
                    )
                    return@withContext SyncResult(
                        success = true,
                        message = "Loaded bundled protection snapshot with $numberCount numbers and $prefixCount prefixes while GitHub was unavailable.",
                        warning = true
                    )
                }

                val bundledError = bundledDatabase.exceptionOrNull()?.message
                val message = buildString {
                    append("Sync unavailable (")
                    append(remoteError)
                    append(")")
                    if (!bundledError.isNullOrBlank()) {
                        append(". Bundled fallback failed: ")
                        append(bundledError)
                    }
                }

                SyncResult(
                    success = false,
                    message = message,
                    shouldRetry = shouldRetrySync(remoteError)
                )
            } catch (e: Exception) {
                SyncResult(
                    success = false,
                    message = "Error: ${e.message}",
                    shouldRetry = true
                )
            }
        } // syncMutex
    }

    private suspend fun loadBundledSpamDatabase(): Result<SpamDatabase> {
        val asset = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_DATABASE_ASSET)
        if (asset.isFailure) {
            return Result.failure(asset.exceptionOrNull()!!)
        }
        return remote.parseSpamDatabaseJson(asset.getOrThrow())
    }

    private suspend fun persistSpamDatabase(
        database: SpamDatabase,
        sha: String?,
        syncSource: String
    ): Pair<Int, Int> {
        val preservedUserBlocks = dao.getUserBlockedNumbersSync()
            .asSequence()
            .map { it.number }
            .toSet()

        val numbers = sanitizeDatabaseNumbers(
            databaseNumbers = database.numbers,
            normalizeNumber = ::normalizeNumber,
            preservedUserBlockedNumbers = preservedUserBlocks
        )
        val prefixes = database.prefixes.mapNotNull { json ->
            val trimmedPrefix = json.prefix.trim()
            if (trimmedPrefix.isBlank()) {
                null
            } else {
                SpamPrefix(
                    prefix = trimmedPrefix,
                    type = json.type.trim().ifBlank { "unknown" },
                    description = json.description.trim()
                )
            }
        }

        dao.replaceGithubData(numbers, prefixes)
        invalidateAllCaches()

        dataStore.edit {
            it[KEY_LAST_SYNC] = System.currentTimeMillis()
            it[KEY_LAST_SYNC_SOURCE] = syncSource
            it[KEY_DB_VERSION] = database.version
            if (sha != null) it[KEY_LAST_SHA] = sha
        }

        CallShieldWidget.refreshAll(context)
        return numbers.size to prefixes.size
    }

    private fun shouldRetrySync(message: String): Boolean {
        val permanentFailureCodes = listOf("HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404")
        return permanentFailureCodes.none { code -> message.contains(code) }
    }

    // ── Blocklist management ───────────────────────────────────────────
    suspend fun blockNumber(number: String, type: String = "unknown", description: String = "") {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        dao.findWhitelistEntry(normalized)?.let { dao.deleteWhitelistEntry(it) }
        val existing = dao.findByNumber(normalized)
        if (existing != null) {
            dao.insertNumber(existing.copy(isUserBlocked = true))
        } else {
            dao.insertNumber(SpamNumber(
                number = normalized,
                type = type.trim().ifBlank { "unknown" },
                description = description.trim(),
                source = "user", isUserBlocked = true
            ))
        }
    }

    suspend fun unblockNumber(number: SpamNumber) {
        if (number.source == "user") dao.deleteNumber(number)
        else dao.insertNumber(number.copy(isUserBlocked = false))
    }

    // ── Wildcard rules (Feature 8) ─────────────────────────────────────
    fun getAllWildcardRules(): Flow<List<WildcardRule>> = dao.getAllWildcardRules()

    suspend fun addWildcardRule(
        pattern: String,
        isRegex: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) {
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isBlank()) return
        dao.insertWildcardRule(
            WildcardRule(
                pattern = trimmedPattern,
                isRegex = isRegex,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, 23),
                scheduleEndHour = schedule.endHour.coerceIn(0, 23),
            )
        )
        invalidateWildcardCache()
    }

    suspend fun deleteWildcardRule(rule: WildcardRule) {
        dao.deleteWildcardRule(rule)
        invalidateWildcardCache()
    }

    suspend fun toggleWildcardRule(id: Long, enabled: Boolean) {
        dao.setWildcardRuleEnabled(id, enabled)
        invalidateWildcardCache()
    }

    // ── Hash wildcard rules (A5, length-locked `#` patterns) ───────────
    fun getAllHashWildcardRules(): Flow<List<HashWildcardRule>> = dao.getAllHashWildcardRules()

    suspend fun addHashWildcardRule(
        pattern: String,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ): Boolean {
        val trimmed = pattern.trim()
        // Minimal validation: at least one `#`, at most 30 chars, pattern is
        // non-empty after trim. Broader validation (overlap, coverage size)
        // is the UI's job — we don't want to reject arbitrary user patterns
        // here unless they literally cannot match anything.
        if (trimmed.isBlank() || trimmed.length > 30) return false
        dao.insertHashWildcardRule(
            HashWildcardRule(
                pattern = trimmed,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, 23),
                scheduleEndHour = schedule.endHour.coerceIn(0, 23),
            )
        )
        invalidateHashWildcardCache()
        return true
    }

    suspend fun deleteHashWildcardRule(rule: HashWildcardRule) {
        dao.deleteHashWildcardRule(rule)
        invalidateHashWildcardCache()
    }

    suspend fun toggleHashWildcardRule(id: Long, enabled: Boolean) {
        dao.setHashWildcardRuleEnabled(id, enabled)
        invalidateHashWildcardCache()
    }

    // ── Call log ───────────────────────────────────────────────────────
    suspend fun logBlockedCall(
        number: String, isCall: Boolean = true, smsBody: String? = null,
        matchReason: String = "", confidence: Int = 100
    ) {
        dao.insertBlockedCall(BlockedCall(
            number = normalizeNumber(number), isCall = isCall,
            smsBody = smsBody, matchReason = matchReason, confidence = confidence
        ))
        // Refresh widget
        CallShieldWidget.refreshAll(context)
        // Send notification
        NotificationHelper.notifyBlocked(context, number, matchReason, isCall)
    }

    fun getBlockedCalls(): Flow<List<BlockedCall>> = dao.getBlockedCalls()
    fun getBlockedCallsOnly(): Flow<List<BlockedCall>> = dao.getBlockedCallsOnly()
    fun getBlockedSmsOnly(): Flow<List<BlockedCall>> = dao.getBlockedSmsOnly()
    fun getTotalBlockedCount(): Flow<Int> = dao.getTotalBlockedCount()
    fun getBlockedCountSince(since: Long): Flow<Int> = dao.getBlockedCountSince(since)
    fun getBlockedCountBetween(start: Long, end: Long): Flow<Int> = dao.getBlockedCountBetween(start, end)
    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = dao.getAllSpamNumbers()
    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> = dao.getUserBlockedNumbers()
    suspend fun getSpamCount(): Int = dao.getSpamCount()
    suspend fun clearCallLog() = dao.clearCallLog()
    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)
    suspend fun insertBlockedCall(call: BlockedCall) = dao.insertBlockedCall(call)

    // ── Search ─────────────────────────────────────────────────────────
    fun searchNumbers(query: String): Flow<List<SpamNumber>> = dao.searchNumbers(escapeLikeQuery(query))

    // ── Whitelist management ───────────────────────────────────────────
    fun getAllWhitelist(): Flow<List<WhitelistEntry>> = dao.getAllWhitelist()

    /** Emergency subset — used by the dedicated Emergency Contacts tab. */
    fun getEmergencyContacts(): Flow<List<WhitelistEntry>> = dao.getEmergencyContacts()

    suspend fun addToWhitelist(number: String, description: String = "", isEmergency: Boolean = false) {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        when (val resolution = resolveSpamNumberForWhitelist(dao.findByNumber(normalized))) {
            SpamNumberWhitelistResolution.None -> Unit
            is SpamNumberWhitelistResolution.Update -> dao.insertNumber(resolution.number)
            is SpamNumberWhitelistResolution.Delete -> dao.deleteNumber(resolution.number)
        }
        dao.insertWhitelistEntry(
            WhitelistEntry(
                number = normalized,
                description = description.trim(),
                isEmergency = isEmergency,
            )
        )
    }

    suspend fun removeFromWhitelist(entry: WhitelistEntry) = dao.deleteWhitelistEntry(entry)

    /** Toggle emergency flag on an existing whitelist entry without deleting it. */
    suspend fun setWhitelistEmergency(id: Long, emergency: Boolean) =
        dao.setWhitelistEmergency(id, emergency)

    // ── Hot list (30-minute trending sync) ────────────────────────────
    suspend fun replaceHotList(numbers: List<SpamNumber>) = withContext(Dispatchers.IO) {
        val hotNumbers = numbers
            .filter { it.number.isNotBlank() }
            .distinctBy { it.number }

        val existingByNumber = if (hotNumbers.isEmpty()) {
            emptyMap()
        } else {
            dao.getNumbersByNumbers(hotNumbers.map { it.number })
                .associateBy { it.number }
        }

        val mergedHotNumbers = mergeHotListNumbers(
            hotNumbers = hotNumbers,
            existingByNumber = existingByNumber
        )

        dao.deleteBySource("hot_list")
        if (mergedHotNumbers.isNotEmpty()) {
            dao.insertNumbers(mergedHotNumbers)
        }
        // Hot list numbers participate in the database-match layer of isSpam(),
        // but prefixes and rules don't change here. No cache invalidation needed
        // since hot list entries are looked up by exact number (dao.findByNumber).
    }

    // ── Auto-cleanup ──────────────────────────────────────────────────
    suspend fun cleanupOldLogs() {
        if (autoCleanupEnabled.first()) {
            val days = cleanupDays.first().coerceAtLeast(7)
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            dao.deleteLogOlderThan(cutoff)
        }
    }

    // ── SMS keyword rules ────────────────────────────────────────────
    fun getAllKeywordRules(): Flow<List<SmsKeywordRule>> = dao.getAllKeywordRules()

    suspend fun addKeywordRule(
        keyword: String,
        caseSensitive: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) return
        dao.insertKeywordRule(
            SmsKeywordRule(
                keyword = trimmedKeyword,
                caseSensitive = caseSensitive,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, 23),
                scheduleEndHour = schedule.endHour.coerceIn(0, 23),
            )
        )
        invalidateKeywordCache()
    }

    suspend fun deleteKeywordRule(rule: SmsKeywordRule) {
        dao.deleteKeywordRule(rule)
        invalidateKeywordCache()
    }
    suspend fun toggleKeywordRule(id: Long, enabled: Boolean) {
        dao.setKeywordRuleEnabled(id, enabled)
        invalidateKeywordCache()
    }

    // ── Search log ───────────────────────────────────────────────────
    fun searchLog(query: String): Flow<List<BlockedCall>> = dao.searchLog(escapeLikeQuery(query))

    fun normalizeNumber(number: String): String {
        val trimmed = number.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> ""
            hasPlus -> "+$digits"
            else -> digits
        }
    }
}

/**
 * Escape the SQL LIKE wildcard characters so user-typed `%` or `_` is
 * treated literally (prevents a blank/"%" search from returning the
 * whole table). Paired with an ESCAPE '\' clause on the Room queries.
 */
internal fun escapeLikeQuery(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Bridge between the new [BlockResult] type returned by the checker
 * pipeline and the older [SpamCheckResult] shape consumed by the rest
 * of the app (UI, service, notification code). Keeps the checker
 * internals decoupled from legacy call sites.
 */
internal fun BlockResult.toSpamCheckResult(): SpamCheckResult = SpamCheckResult(
    isSpam = shouldBlock,
    matchSource = matchSource,
    type = type,
    description = description,
    confidence = confidence,
)

internal fun sanitizeDatabaseNumbers(
    databaseNumbers: Collection<SpamNumberJson>,
    normalizeNumber: (String) -> String,
    preservedUserBlockedNumbers: Set<String>,
): List<SpamNumber> {
    return databaseNumbers.mapNotNull { json ->
        val normalizedNumber = normalizeNumber(json.number)
        if (normalizedNumber.isBlank()) {
            null
        } else {
            SpamNumber(
                number = normalizedNumber,
                type = json.type.trim().ifBlank { "unknown" },
                reports = json.reports.coerceAtLeast(1),
                firstSeen = json.firstSeen,
                lastSeen = json.lastSeen,
                description = json.description.trim(),
                source = "github",
                isUserBlocked = normalizedNumber in preservedUserBlockedNumbers
            )
        }
    }
}

internal fun mergeHotListNumbers(
    hotNumbers: Collection<SpamNumber>,
    existingByNumber: Map<String, SpamNumber>,
): List<SpamNumber> {
    return hotNumbers.mapNotNull { hotNumber ->
        when (val existing = existingByNumber[hotNumber.number]) {
            null -> hotNumber
            else -> {
                // Never let ephemeral hot-list data overwrite a stronger row from
                // the main database. If we already know this number from GitHub or
                // from a user-owned entry, keep that record and skip the hot insert.
                if (existing.source != "hot_list") {
                    null
                } else {
                    hotNumber.copy(
                        id = existing.id,
                        isUserBlocked = existing.isUserBlocked
                    )
                }
            }
        }
    }
}

internal sealed interface SpamNumberWhitelistResolution {
    data object None : SpamNumberWhitelistResolution
    data class Update(val number: SpamNumber) : SpamNumberWhitelistResolution
    data class Delete(val number: SpamNumber) : SpamNumberWhitelistResolution
}

internal fun resolveSpamNumberForWhitelist(existing: SpamNumber?): SpamNumberWhitelistResolution {
    if (existing == null || !existing.isUserBlocked) {
        return SpamNumberWhitelistResolution.None
    }

    return if (existing.source == "user") {
        SpamNumberWhitelistResolution.Delete(existing)
    } else {
        SpamNumberWhitelistResolution.Update(existing.copy(isUserBlocked = false))
    }
}

data class SpamCheckResult(
    val isSpam: Boolean,
    val matchSource: String = "",
    val type: String = "",
    val description: String = "",
    val confidence: Int = 100
)

data class SyncResult(
    val success: Boolean,
    val message: String,
    val warning: Boolean = false,
    val shouldRetry: Boolean = false
)
