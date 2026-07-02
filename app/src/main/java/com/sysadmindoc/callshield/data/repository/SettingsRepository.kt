package com.sysadmindoc.callshield.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val privateDataStore: DataStore<Preferences>,
) {
    private companion object {
        const val DEFAULT_TIME_BLOCK_START = 22
        const val DEFAULT_TIME_BLOCK_END = 7
        const val DEFAULT_FREQUENCY_THRESHOLD = 3
        const val DEFAULT_CLEANUP_DAYS = 30
    }

    private val abstractApiKeyMigrationMutex = Mutex()
    @Volatile private var abstractApiKeyMigrationComplete = false

    val blockCallsEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_BLOCK_CALLS] ?: true }
    val blockSmsEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_BLOCK_SMS] ?: true }
    val blockUnknownEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false }
    val stirShakenEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_STIR_SHAKEN] ?: true }
    val stirTrustedAllowEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_STIR_TRUSTED_ALLOW] ?: true }
    val autoMuteLowConfidenceEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false }
    val neighborSpoofEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_NEIGHBOR_SPOOF] ?: true }
    val heuristicsEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_HEURISTICS] ?: true }
    val smsContentEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_SMS_CONTENT] ?: true }
    val contactWhitelistEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_CONTACT_WHITELIST] ?: true }
    val contactsOnlyEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_CONTACTS_ONLY] ?: false }
    val dbPrefixExpansionEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_DB_PREFIX_EXPANSION] ?: false }
    val aggressiveModeEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false }
    val answeredCallerTrustEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_ANSWERED_CALLER_TRUST] ?: true }
    val answeredCallerThreshold: Flow<Int> =
        dataStore.data.map {
            it[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD]
                ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD
        }
    val answeredCallerWindowDays: Flow<Int> =
        dataStore.data.map {
            it[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS]
                ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS
        }
    val timeBlockEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_TIME_BLOCK] ?: false }
    val timeBlockStart: Flow<Int> =
        dataStore.data.map { it[SpamRepository.KEY_TIME_BLOCK_START] ?: DEFAULT_TIME_BLOCK_START }
    val timeBlockEnd: Flow<Int> =
        dataStore.data.map { it[SpamRepository.KEY_TIME_BLOCK_END] ?: DEFAULT_TIME_BLOCK_END }
    val freqEscalationEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_FREQ_ESCALATION] ?: true }
    val freqThreshold: Flow<Int> =
        dataStore.data.map { it[SpamRepository.KEY_FREQ_THRESHOLD] ?: DEFAULT_FREQUENCY_THRESHOLD }
    val onboardingDone: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_ONBOARDING_DONE] ?: false }
    val autoCleanupEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_AUTO_CLEANUP] ?: false }
    val cleanupDays: Flow<Int> = dataStore.data.map { it[SpamRepository.KEY_CLEANUP_DAYS] ?: DEFAULT_CLEANUP_DAYS }
    val mlScorerEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_ML_SCORER] ?: true }
    val rcsFilterEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_RCS_FILTER] ?: true }
    val silentVoicemailEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false }
    val pushAlertEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_PUSH_ALERT] ?: true }
    val pushAlertDisabledPackages: Flow<Set<String>> =
        dataStore.data.map { it[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet() }
    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it[SpamRepository.KEY_LAST_SYNC] ?: 0L }
    val lastSyncSource: Flow<String> = dataStore.data.map { it[SpamRepository.KEY_LAST_SYNC_SOURCE] ?: "" }
    val activeProfileName: Flow<String?> = dataStore.data.map { it[SpamRepository.KEY_ACTIVE_PROFILE] }

    suspend fun setActiveProfileName(name: String?) = dataStore.edit { prefs ->
        if (name == null) prefs.remove(SpamRepository.KEY_ACTIVE_PROFILE)
        else prefs[SpamRepository.KEY_ACTIVE_PROFILE] = name
    }

    // Optional AbstractAPI key for carrier/number-type enrichment in the Caller ID overlay.
    // Never used in the blocking pipeline; blocking stays local/offline.
    val abstractApiKey: Flow<String> = flow {
        migrateAbstractApiKeyIfNeeded()
        emitAll(privateDataStore.data.map { it[SpamRepository.KEY_ABSTRACT_API_KEY] ?: "" })
    }

    suspend fun setAbstractApiKey(key: String) {
        migrateAbstractApiKeyIfNeeded()
        val sanitizedKey = key.trim()
        privateDataStore.edit { prefs ->
            if (sanitizedKey.isBlank()) {
                prefs.remove(SpamRepository.KEY_ABSTRACT_API_KEY)
            } else {
                prefs[SpamRepository.KEY_ABSTRACT_API_KEY] = sanitizedKey
            }
        }
        dataStore.edit { it.remove(SpamRepository.KEY_ABSTRACT_API_KEY) }
    }

    suspend fun setMlScorer(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_ML_SCORER] = enabled }
    suspend fun setRcsFilter(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_RCS_FILTER] = enabled }
    suspend fun setSilentVoicemail(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_SILENT_VOICEMAIL] = enabled }
    suspend fun setPushAlert(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_PUSH_ALERT] = enabled }

    suspend fun togglePushAlertPackage(pkg: String, allowed: Boolean) = dataStore.edit { prefs ->
        val current = prefs[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet()
        val next = if (allowed) current - pkg else current + pkg
        if (next.isEmpty()) {
            prefs.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED)
        } else {
            prefs[SpamRepository.KEY_PUSH_ALERT_DISABLED] = next
        }
    }

    suspend fun resetPushAlertPackages() =
        dataStore.edit { it.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED) }

    suspend fun setOnboardingDone() = dataStore.edit { it[SpamRepository.KEY_ONBOARDING_DONE] = true }
    suspend fun setAutoCleanup(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_AUTO_CLEANUP] = enabled }
    suspend fun setCleanupDays(days: Int) = dataStore.edit { it[SpamRepository.KEY_CLEANUP_DAYS] = days }
    suspend fun setBlockCalls(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_BLOCK_CALLS] = enabled }
    suspend fun setBlockSms(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_BLOCK_SMS] = enabled }
    suspend fun setBlockUnknown(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_BLOCK_UNKNOWN] = enabled }
    suspend fun setStirShaken(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_STIR_SHAKEN] = enabled }
    suspend fun setStirTrustedAllow(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_STIR_TRUSTED_ALLOW] = enabled }
    suspend fun setAutoMuteLowConfidence(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] = enabled }
    suspend fun setNeighborSpoof(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_NEIGHBOR_SPOOF] = enabled }
    suspend fun setHeuristics(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_HEURISTICS] = enabled }
    suspend fun setSmsContent(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_SMS_CONTENT] = enabled }
    suspend fun setContactWhitelist(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_CONTACT_WHITELIST] = enabled }
    suspend fun setContactsOnly(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_CONTACTS_ONLY] = enabled }
    suspend fun setDbPrefixExpansion(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_DB_PREFIX_EXPANSION] = enabled }
    suspend fun setAggressiveMode(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_AGGRESSIVE_MODE] = enabled }
    suspend fun setAnsweredCallerTrust(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_ANSWERED_CALLER_TRUST] = enabled }
    suspend fun setAnsweredCallerThreshold(threshold: Int) =
        dataStore.edit {
            it[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD] = threshold.coerceIn(
                ANSWERED_CALLER_THRESHOLD_MIN,
                ANSWERED_CALLER_THRESHOLD_MAX,
            )
        }
    suspend fun setAnsweredCallerWindowDays(days: Int) =
        dataStore.edit {
            it[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS] = days.coerceIn(
                ANSWERED_CALLER_WINDOW_DAYS_MIN,
                ANSWERED_CALLER_WINDOW_DAYS_MAX,
            )
        }
    suspend fun setTimeBlock(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK] = enabled }
    suspend fun setTimeBlockStart(hour: Int) =
        dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK_START] = hour }
    suspend fun setTimeBlockEnd(hour: Int) =
        dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK_END] = hour }
    suspend fun setFreqEscalation(enabled: Boolean) =
        dataStore.edit { it[SpamRepository.KEY_FREQ_ESCALATION] = enabled }

    suspend fun readPrefsSnapshot(): Preferences = dataStore.data.first()

    suspend fun readLastDataSha(): String? = dataStore.data.first()[SpamRepository.KEY_LAST_SHA]

    suspend fun recordSyncSuccess(
        sha: String?,
        syncSource: String,
        databaseVersion: Int,
    ) = dataStore.edit {
        it[SpamRepository.KEY_LAST_SYNC] = System.currentTimeMillis()
        it[SpamRepository.KEY_LAST_SYNC_SOURCE] = syncSource
        it[SpamRepository.KEY_DB_VERSION] = databaseVersion
        if (sha != null) it[SpamRepository.KEY_LAST_SHA] = sha
    }

    private suspend fun migrateAbstractApiKeyIfNeeded() {
        if (abstractApiKeyMigrationComplete) return

        abstractApiKeyMigrationMutex.withLock {
            if (abstractApiKeyMigrationComplete) return@withLock

            val privateKey = privateDataStore.data.first()[SpamRepository.KEY_ABSTRACT_API_KEY]
            val legacyKey = dataStore.data.first()[SpamRepository.KEY_ABSTRACT_API_KEY]
            if (privateKey.isNullOrBlank() && !legacyKey.isNullOrBlank()) {
                privateDataStore.edit { it[SpamRepository.KEY_ABSTRACT_API_KEY] = legacyKey.trim() }
            }
            if (legacyKey != null) {
                dataStore.edit { it.remove(SpamRepository.KEY_ABSTRACT_API_KEY) }
            }
            abstractApiKeyMigrationComplete = true
        }
    }
}

internal const val ANSWERED_CALLER_THRESHOLD_MIN = 1
internal const val ANSWERED_CALLER_THRESHOLD_MAX = 10
internal const val ANSWERED_CALLER_WINDOW_DAYS_MIN = 1
internal const val ANSWERED_CALLER_WINDOW_DAYS_MAX = 365
