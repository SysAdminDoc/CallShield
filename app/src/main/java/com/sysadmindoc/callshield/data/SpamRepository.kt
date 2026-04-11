package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.*
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.service.CallerIdOverlayService
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "callshield_prefs")

class SpamRepository(private val context: Context) {
    private val dao: SpamDao = AppDatabase.getInstance(context).spamDao()
    private val remote = GitHubDataSource()
    private val dataStore = context.dataStore
    private val syncMutex = Mutex()

    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        private val KEY_LAST_SYNC_SOURCE = stringPreferencesKey("last_sync_source")
        private val KEY_LAST_SHA = stringPreferencesKey("last_data_sha")
        private val KEY_DB_VERSION = intPreferencesKey("db_version")
        private val KEY_BLOCK_CALLS = booleanPreferencesKey("block_calls_enabled")
        private val KEY_BLOCK_SMS = booleanPreferencesKey("block_sms_enabled")
        private val KEY_BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown_enabled")
        private val KEY_STIR_SHAKEN = booleanPreferencesKey("stir_shaken_enabled")
        private val KEY_NEIGHBOR_SPOOF = booleanPreferencesKey("neighbor_spoof_enabled")
        private val KEY_HEURISTICS = booleanPreferencesKey("heuristics_enabled")
        private val KEY_SMS_CONTENT = booleanPreferencesKey("sms_content_analysis_enabled")
        private val KEY_CONTACT_WHITELIST = booleanPreferencesKey("contact_whitelist_enabled")
        private val KEY_AGGRESSIVE_MODE = booleanPreferencesKey("aggressive_mode_enabled")
        // Feature 9: Time-based blocking
        private val KEY_TIME_BLOCK = booleanPreferencesKey("time_block_enabled")
        private val KEY_TIME_BLOCK_START = intPreferencesKey("time_block_start_hour") // 0-23
        private val KEY_TIME_BLOCK_END = intPreferencesKey("time_block_end_hour")
        // Feature 10: Frequency auto-escalation
        private val KEY_FREQ_ESCALATION = booleanPreferencesKey("freq_escalation_enabled")
        private val KEY_FREQ_THRESHOLD = intPreferencesKey("freq_threshold")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup_enabled")
        private val KEY_CLEANUP_DAYS = intPreferencesKey("cleanup_retention_days")
        private val KEY_ABSTRACT_API_KEY = stringPreferencesKey("abstract_api_key")
        private val KEY_ML_SCORER = booleanPreferencesKey("ml_scorer_enabled")
        private val KEY_RCS_FILTER = booleanPreferencesKey("rcs_filter_enabled")

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
    suspend fun setMlScorer(enabled: Boolean) = dataStore.edit { it[KEY_ML_SCORER] = enabled }
    suspend fun setRcsFilter(enabled: Boolean) = dataStore.edit { it[KEY_RCS_FILTER] = enabled }

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
    suspend fun setNeighborSpoof(enabled: Boolean) = dataStore.edit { it[KEY_NEIGHBOR_SPOOF] = enabled }
    suspend fun setHeuristics(enabled: Boolean) = dataStore.edit { it[KEY_HEURISTICS] = enabled }
    suspend fun setSmsContent(enabled: Boolean) = dataStore.edit { it[KEY_SMS_CONTENT] = enabled }
    suspend fun setContactWhitelist(enabled: Boolean) = dataStore.edit { it[KEY_CONTACT_WHITELIST] = enabled }
    suspend fun setAggressiveMode(enabled: Boolean) = dataStore.edit { it[KEY_AGGRESSIVE_MODE] = enabled }
    suspend fun setTimeBlock(enabled: Boolean) = dataStore.edit { it[KEY_TIME_BLOCK] = enabled }
    suspend fun setTimeBlockStart(hour: Int) = dataStore.edit { it[KEY_TIME_BLOCK_START] = hour }
    suspend fun setTimeBlockEnd(hour: Int) = dataStore.edit { it[KEY_TIME_BLOCK_END] = hour }
    suspend fun setFreqEscalation(enabled: Boolean) = dataStore.edit { it[KEY_FREQ_ESCALATION] = enabled }

    // ── Primary spam check ─────────────────────────────────────────────
    suspend fun isSpam(number: String, smsBody: String? = null): SpamCheckResult {
        val normalized = normalizeNumber(number)

        // Record every incoming number for campaign burst detection
        CampaignDetector.recordCall(normalized)

        // Manual whitelist — always allow
        if (dao.findWhitelistEntry(normalized) != null) {
            return SpamCheckResult(false, matchSource = "manual_whitelist")
        }

        // Contact whitelist
        if (contactWhitelistEnabled.first() && SpamHeuristics.isInContacts(context, normalized)) {
            return SpamCheckResult(false, matchSource = "contact_whitelist")
        }

        // Dialed number recognition — don't block callbacks from numbers user recently called
        if (CallbackDetector.wasRecentlyDialed(context, normalized)) {
            return SpamCheckResult(false, matchSource = "recently_dialed")
        }

        // Repeated call allow-through — if same number calls 2x in 5 min, likely urgent
        if (CallbackDetector.isRepeatedUrgentCall(context, normalized)) {
            return SpamCheckResult(false, matchSource = "repeated_urgent")
        }

        // User blocklist + database match (single query)
        val dbEntry = dao.findByNumber(normalized)
        if (dbEntry != null) {
            if (dbEntry.isUserBlocked) {
                return SpamCheckResult(true, "user_blocklist", dbEntry.type, dbEntry.description)
            }
            return SpamCheckResult(true, "database", dbEntry.type, dbEntry.description)
        }

        // Prefix match
        val prefixes = dao.getAllPrefixes()
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix.prefix)) {
                return SpamCheckResult(true, "prefix", prefix.type, prefix.description)
            }
        }

        // Feature 8: Wildcard/regex rules
        val wildcards = dao.getActiveWildcardRules()
        for (rule in wildcards) {
            if (rule.matches(normalized)) {
                return SpamCheckResult(true, "wildcard", "blocked", rule.description)
            }
        }

        // Feature 9: Time-based blocking (block all non-contact unknowns during sleep hours)
        if (timeBlockEnabled.first() && isInBlockedTimeWindow()) {
            return SpamCheckResult(true, "time_block", "unknown", "Blocked during quiet hours")
        }

        // Feature 10: Frequency auto-escalation
        if (freqEscalationEnabled.first()) {
            val freq = dao.getNumberFrequency(normalized)
            val threshold = freqThreshold.first().coerceAtLeast(2)
            if (freq >= threshold) {
                return SpamCheckResult(true, "frequency", "repeat_caller", "Called $freq times - auto-blocked")
            }
        }

        // Heuristic engine
        if (heuristicsEnabled.first()) {
            val recentBlocked = dao.getRecentBlockedNumbers(System.currentTimeMillis() - 3600_000)
            val hResult = SpamHeuristics.analyze(
                context = context,
                number = normalized,
                smsBody = if (smsContentEnabled.first()) smsBody else null,
                recentBlockedNumbers = recentBlocked.map { it.number to it.timestamp }
            )

            val threshold = if (aggressiveModeEnabled.first()) 30 else 60

            if (hResult.score >= threshold) {
                return SpamCheckResult(
                    isSpam = true,
                    matchSource = "heuristic",
                    type = classifyHeuristicReasons(hResult.reasons),
                    description = hResult.reasons.joinToString(", ") { it.replace("_", " ") },
                    confidence = hResult.score
                )
            }

            // Feature 7: Caller ID overlay for suspicious but not blocked
            if (hResult.score >= 30 && hResult.score < threshold) {
                showCallerIdOverlay(normalized, hResult.score, hResult.reasons.firstOrNull() ?: "suspicious")
            }
        }

        // Layer 11.5: Campaign burst detection
        if (CampaignDetector.isActiveCampaign(normalized)) {
            return SpamCheckResult(
                isSpam = true,
                matchSource = "campaign_burst",
                type = "robocall",
                description = "Active spam campaign detected from this prefix",
                confidence = 75
            )
        }

        // Layer 15: On-device ML spam scorer
        if (mlScorerEnabled.first() && SpamMLScorer.isSpam(normalized)) {
            val mlConf = SpamMLScorer.confidence(normalized)
            return SpamCheckResult(
                isSpam = true,
                matchSource = "ml_scorer",
                type = "robocall",
                description = "ML model: ${mlConf}% spam probability",
                confidence = mlConf
            )
        }

        return SpamCheckResult(false)
    }

    // ── SMS-specific check ─────────────────────────────────────────────
    suspend fun isSpamSms(number: String, body: String): SpamCheckResult {
        val numberResult = isSpam(number, smsBody = body)
        if (numberResult.isSpam) return numberResult

        // Prior conversation trust — if user has sent to or regularly received
        // from this number, bypass keyword/content analysis entirely.
        // Checked after number-based blocking so a user-blocked number still blocks.
        if (SmsContextChecker.isTrustedSender(context, number)) {
            return SpamCheckResult(false, matchSource = "sms_context")
        }

        // Custom SMS keyword rules
        val keywords = dao.getActiveKeywordRules()
        for (rule in keywords) {
            if (rule.matches(body)) {
                return SpamCheckResult(true, "keyword", "sms_spam", "Keyword: ${rule.keyword}")
            }
        }

        if (smsContentEnabled.first()) {
            val smsResult = SmsContentAnalyzer.analyze(body)
            val threshold = if (aggressiveModeEnabled.first()) 25 else 50
            if (smsResult.score >= threshold) {
                return SpamCheckResult(
                    isSpam = true,
                    matchSource = "sms_content",
                    type = "sms_spam",
                    description = smsResult.reasons.joinToString(", ") { it.replace("_", " ") },
                    confidence = smsResult.score
                )
            }
        }

        return SpamCheckResult(false)
    }

    private suspend fun isInBlockedTimeWindow(): Boolean {
        val start = timeBlockStart.first().coerceIn(0, 23)
        val end = timeBlockEnd.first().coerceIn(0, 23)
        if (start == end) return false // Same hour = disabled
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // End hour is exclusive: "22 to 7" means block 22:00-6:59, allow at 7:00
        return if (start < end) {
            now >= start && now < end
        } else {
            now >= start || now < end // e.g., 22-7: block 22:00-6:59
        }
    }

    private fun showCallerIdOverlay(number: String, confidence: Int, reason: String) {
        try {
            val intent = Intent(context, CallerIdOverlayService::class.java).apply {
                putExtra("number", number)
                putExtra("confidence", confidence)
                putExtra("reason", reason)
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun classifyHeuristicReasons(reasons: List<String>): String {
        return when {
            "premium_rate" in reasons -> "premium_scam"
            "wangiri_country" in reasons -> "wangiri_scam"
            "neighbor_spoof" in reasons -> "spoofed"
            "rapid_fire" in reasons -> "robocall"
            "spam_keywords" in reasons -> "sms_spam"
            "shortened_url" in reasons || "suspicious_tld" in reasons -> "phishing"
            "voip_spam_range" in reasons -> "robocall"
            else -> "suspicious"
        }
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
        val numbers = database.numbers.mapNotNull { json ->
            val normalizedNumber = normalizeNumber(json.number)
            if (normalizedNumber.isBlank()) {
                null
            } else {
                SpamNumber(
                    number = normalizedNumber,
                    type = json.type.trim().ifBlank { "unknown" },
                    reports = json.reports,
                    firstSeen = json.firstSeen,
                    lastSeen = json.lastSeen,
                    description = json.description.trim(),
                    source = "github"
                )
            }
        }
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

    suspend fun addWildcardRule(pattern: String, isRegex: Boolean = false, description: String = "") {
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isBlank()) return
        dao.insertWildcardRule(
            WildcardRule(
                pattern = trimmedPattern,
                isRegex = isRegex,
                description = description.trim()
            )
        )
    }

    suspend fun deleteWildcardRule(rule: WildcardRule) = dao.deleteWildcardRule(rule)

    suspend fun toggleWildcardRule(id: Long, enabled: Boolean) = dao.setWildcardRuleEnabled(id, enabled)

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

    // Feature 4: After-call spam rating for unblocked unknown numbers
    fun promptSpamRating(number: String) {
        if (number.isNotEmpty()) {
            NotificationHelper.notifySpamRating(context, number)
        }
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
    fun searchNumbers(query: String): Flow<List<SpamNumber>> = dao.searchNumbers(query)

    // ── Whitelist management ───────────────────────────────────────────
    fun getAllWhitelist(): Flow<List<WhitelistEntry>> = dao.getAllWhitelist()

    suspend fun addToWhitelist(number: String, description: String = "") {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        dao.insertWhitelistEntry(WhitelistEntry(number = normalized, description = description.trim()))
    }

    suspend fun removeFromWhitelist(entry: WhitelistEntry) = dao.deleteWhitelistEntry(entry)

    // ── Hot list (30-minute trending sync) ────────────────────────────
    suspend fun replaceHotList(numbers: List<SpamNumber>) = withContext(Dispatchers.IO) {
        dao.replaceBySource("hot_list", numbers)
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

    suspend fun addKeywordRule(keyword: String, caseSensitive: Boolean = false, description: String = "") {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) return
        dao.insertKeywordRule(
            SmsKeywordRule(
                keyword = trimmedKeyword,
                caseSensitive = caseSensitive,
                description = description.trim()
            )
        )
    }

    suspend fun deleteKeywordRule(rule: SmsKeywordRule) = dao.deleteKeywordRule(rule)
    suspend fun toggleKeywordRule(id: Long, enabled: Boolean) = dao.setKeywordRuleEnabled(id, enabled)

    // ── Search log ───────────────────────────────────────────────────
    fun searchLog(query: String): Flow<List<BlockedCall>> = dao.searchLog(query)

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
