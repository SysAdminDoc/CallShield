package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.*
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "callshield_prefs")

class SpamRepository(private val context: Context) {
    private val dao: SpamDao = AppDatabase.getInstance(context).spamDao()
    private val remote = GitHubDataSource()
    private val dataStore = context.dataStore

    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
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

    suspend fun setBlockCalls(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_CALLS] = enabled }
    suspend fun setBlockSms(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_SMS] = enabled }
    suspend fun setBlockUnknown(enabled: Boolean) = dataStore.edit { it[KEY_BLOCK_UNKNOWN] = enabled }
    suspend fun setStirShaken(enabled: Boolean) = dataStore.edit { it[KEY_STIR_SHAKEN] = enabled }
    suspend fun setNeighborSpoof(enabled: Boolean) = dataStore.edit { it[KEY_NEIGHBOR_SPOOF] = enabled }
    suspend fun setHeuristics(enabled: Boolean) = dataStore.edit { it[KEY_HEURISTICS] = enabled }
    suspend fun setSmsContent(enabled: Boolean) = dataStore.edit { it[KEY_SMS_CONTENT] = enabled }
    suspend fun setContactWhitelist(enabled: Boolean) = dataStore.edit { it[KEY_CONTACT_WHITELIST] = enabled }
    suspend fun setAggressiveMode(enabled: Boolean) = dataStore.edit { it[KEY_AGGRESSIVE_MODE] = enabled }

    // ── Primary spam check (calls) ─────────────────────────────────────
    suspend fun isSpam(number: String, smsBody: String? = null): SpamCheckResult {
        val normalized = normalizeNumber(number)

        // Contact whitelist — always check first, always pass
        if (contactWhitelistEnabled.first() && SpamHeuristics.isInContacts(context, normalized)) {
            return SpamCheckResult(false, matchSource = "contact_whitelist")
        }

        // User blocklist
        val userBlocked = dao.findByNumber(normalized)
        if (userBlocked?.isUserBlocked == true) {
            return SpamCheckResult(true, "user_blocklist", userBlocked.type, userBlocked.description)
        }

        // Database match (exact number)
        val dbMatch = dao.findByNumber(normalized)
        if (dbMatch != null) {
            return SpamCheckResult(true, "database", dbMatch.type, dbMatch.description)
        }

        // Prefix match
        val prefixes = dao.getAllPrefixes()
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix.prefix)) {
                return SpamCheckResult(true, "prefix", prefix.type, prefix.description)
            }
        }

        // Heuristic engine (on-device analysis)
        if (heuristicsEnabled.first()) {
            val recentBlocked = dao.getRecentBlockedNumbers(System.currentTimeMillis() - 3600_000)
            val hResult = SpamHeuristics.analyze(
                context = context,
                number = normalized,
                smsBody = if (smsContentEnabled.first()) smsBody else null,
                recentBlockedNumbers = recentBlocked.map { it.number to it.timestamp }
            )

            // In aggressive mode, lower the threshold
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
        }

        return SpamCheckResult(false)
    }

    // ── SMS-specific check (includes content analysis) ─────────────────
    suspend fun isSpamSms(number: String, body: String): SpamCheckResult {
        // First run the standard number check with SMS body
        val numberResult = isSpam(number, smsBody = body)
        if (numberResult.isSpam) return numberResult

        // Additional SMS-only content analysis even if number is clean
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

    // Sync from GitHub
    suspend fun syncFromGitHub(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val currentSha = dataStore.data.first()[KEY_LAST_SHA]
            val remoteResult = remote.checkForUpdate()

            if (remoteResult.isSuccess) {
                val remoteSha = remoteResult.getOrThrow()
                if (remoteSha == currentSha) {
                    return@withContext SyncResult(false, "Already up to date")
                }
            }

            val result = remote.fetchSpamDatabase()
            if (result.isFailure) {
                return@withContext SyncResult(false, "Failed: ${result.exceptionOrNull()?.message}")
            }

            val database = result.getOrThrow()

            dao.deleteBySource("github")
            dao.deleteAllPrefixes()

            val numbers = database.numbers.map { json ->
                SpamNumber(
                    number = normalizeNumber(json.number),
                    type = json.type,
                    reports = json.reports,
                    firstSeen = json.firstSeen,
                    lastSeen = json.lastSeen,
                    description = json.description,
                    source = "github"
                )
            }
            dao.insertNumbers(numbers)

            val prefixes = database.prefixes.map { json ->
                SpamPrefix(
                    prefix = json.prefix,
                    type = json.type,
                    description = json.description
                )
            }
            dao.insertPrefixes(prefixes)

            dataStore.edit {
                it[KEY_LAST_SYNC] = System.currentTimeMillis()
                it[KEY_DB_VERSION] = database.version
                if (remoteResult.isSuccess) {
                    it[KEY_LAST_SHA] = remoteResult.getOrThrow()
                }
            }

            SyncResult(true, "Synced ${numbers.size} numbers, ${prefixes.size} prefixes")
        } catch (e: Exception) {
            SyncResult(false, "Error: ${e.message}")
        }
    }

    // User blocklist management
    suspend fun blockNumber(number: String, type: String = "unknown", description: String = "") {
        val normalized = normalizeNumber(number)
        val existing = dao.findByNumber(normalized)
        if (existing != null) {
            dao.insertNumber(existing.copy(isUserBlocked = true))
        } else {
            dao.insertNumber(
                SpamNumber(
                    number = normalized,
                    type = type,
                    description = description,
                    source = "user",
                    isUserBlocked = true
                )
            )
        }
    }

    suspend fun unblockNumber(number: SpamNumber) {
        if (number.source == "user") {
            dao.deleteNumber(number)
        } else {
            dao.insertNumber(number.copy(isUserBlocked = false))
        }
    }

    // Call log
    suspend fun logBlockedCall(
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100
    ) {
        dao.insertBlockedCall(
            BlockedCall(
                number = normalizeNumber(number),
                isCall = isCall,
                smsBody = smsBody,
                matchReason = matchReason,
                confidence = confidence
            )
        )
    }

    fun getBlockedCalls(): Flow<List<BlockedCall>> = dao.getBlockedCalls()
    fun getBlockedCallsOnly(): Flow<List<BlockedCall>> = dao.getBlockedCallsOnly()
    fun getBlockedSmsOnly(): Flow<List<BlockedCall>> = dao.getBlockedSmsOnly()
    fun getTotalBlockedCount(): Flow<Int> = dao.getTotalBlockedCount()
    fun getBlockedCountSince(since: Long): Flow<Int> = dao.getBlockedCountSince(since)
    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = dao.getAllSpamNumbers()
    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> = dao.getUserBlockedNumbers()
    suspend fun getSpamCount(): Int = dao.getSpamCount()
    suspend fun clearCallLog() = dao.clearCallLog()
    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)

    private fun normalizeNumber(number: String): String {
        val hasPlus = number.startsWith("+")
        val digits = number.filter { it.isDigit() }
        return if (hasPlus) "+$digits" else digits
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
    val message: String
)
