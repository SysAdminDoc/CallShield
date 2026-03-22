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
import kotlinx.coroutines.withContext
import java.util.Calendar

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
        // Feature 9: Time-based blocking
        private val KEY_TIME_BLOCK = booleanPreferencesKey("time_block_enabled")
        private val KEY_TIME_BLOCK_START = intPreferencesKey("time_block_start_hour") // 0-23
        private val KEY_TIME_BLOCK_END = intPreferencesKey("time_block_end_hour")
        // Feature 10: Frequency auto-escalation
        private val KEY_FREQ_ESCALATION = booleanPreferencesKey("freq_escalation_enabled")
        private val KEY_FREQ_THRESHOLD = intPreferencesKey("freq_threshold")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

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

    suspend fun setOnboardingDone() = dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
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

        // Manual whitelist — always allow
        if (dao.findWhitelistEntry(normalized) != null) {
            return SpamCheckResult(false, matchSource = "manual_whitelist")
        }

        // Contact whitelist
        if (contactWhitelistEnabled.first() && SpamHeuristics.isInContacts(context, normalized)) {
            return SpamCheckResult(false, matchSource = "contact_whitelist")
        }

        // User blocklist
        val userBlocked = dao.findByNumber(normalized)
        if (userBlocked?.isUserBlocked == true) {
            return SpamCheckResult(true, "user_blocklist", userBlocked.type, userBlocked.description)
        }

        // Database match
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
            val threshold = freqThreshold.first()
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

        return SpamCheckResult(false)
    }

    // ── SMS-specific check ─────────────────────────────────────────────
    suspend fun isSpamSms(number: String, body: String): SpamCheckResult {
        val numberResult = isSpam(number, smsBody = body)
        if (numberResult.isSpam) return numberResult

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
        val start = timeBlockStart.first()
        val end = timeBlockEnd.first()
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return if (start <= end) {
            now in start until end // e.g., 9-17
        } else {
            now >= start || now < end // e.g., 22-7 (overnight)
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
                    type = json.type, reports = json.reports,
                    firstSeen = json.firstSeen, lastSeen = json.lastSeen,
                    description = json.description, source = "github"
                )
            }
            dao.insertNumbers(numbers)

            val prefixes = database.prefixes.map { json ->
                SpamPrefix(prefix = json.prefix, type = json.type, description = json.description)
            }
            dao.insertPrefixes(prefixes)

            dataStore.edit {
                it[KEY_LAST_SYNC] = System.currentTimeMillis()
                it[KEY_DB_VERSION] = database.version
                if (remoteResult.isSuccess) it[KEY_LAST_SHA] = remoteResult.getOrThrow()
            }

            // Refresh widget after sync
            CallShieldWidget.refreshAll(context)

            SyncResult(true, "Synced ${numbers.size} numbers, ${prefixes.size} prefixes")
        } catch (e: Exception) {
            SyncResult(false, "Error: ${e.message}")
        }
    }

    // ── Blocklist management ───────────────────────────────────────────
    suspend fun blockNumber(number: String, type: String = "unknown", description: String = "") {
        val normalized = normalizeNumber(number)
        val existing = dao.findByNumber(normalized)
        if (existing != null) {
            dao.insertNumber(existing.copy(isUserBlocked = true))
        } else {
            dao.insertNumber(SpamNumber(
                number = normalized, type = type, description = description,
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
        dao.insertWildcardRule(WildcardRule(pattern = pattern, isRegex = isRegex, description = description))
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
    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = dao.getAllSpamNumbers()
    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> = dao.getUserBlockedNumbers()
    suspend fun getSpamCount(): Int = dao.getSpamCount()
    suspend fun clearCallLog() = dao.clearCallLog()
    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)

    // ── Search ─────────────────────────────────────────────────────────
    fun searchNumbers(query: String): Flow<List<SpamNumber>> = dao.searchNumbers(query)

    // ── Whitelist management ───────────────────────────────────────────
    fun getAllWhitelist(): Flow<List<WhitelistEntry>> = dao.getAllWhitelist()

    suspend fun addToWhitelist(number: String, description: String = "") {
        dao.insertWhitelistEntry(WhitelistEntry(number = normalizeNumber(number), description = description))
    }

    suspend fun removeFromWhitelist(entry: WhitelistEntry) = dao.deleteWhitelistEntry(entry)

    fun normalizeNumber(number: String): String {
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

data class SyncResult(val success: Boolean, val message: String)
