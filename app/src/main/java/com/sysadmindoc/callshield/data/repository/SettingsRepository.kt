package com.sysadmindoc.callshield.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.CategoryCallPolicy
import com.sysadmindoc.callshield.data.ContactGroupCatalog
import com.sysadmindoc.callshield.data.NotificationScreeningSources
import com.sysadmindoc.callshield.data.RegionRules
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val validAppThemes = setOf("system", "light", "graphite", "amoled")

internal fun sanitizeAppTheme(value: String?): String = value?.takeIf(validAppThemes::contains) ?: "light"

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

    private val externalBlocklistAdapter =
        Moshi
            .Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter<List<ExternalBlocklistSubscription>>(
                Types.newParameterizedType(
                    List::class.java,
                    ExternalBlocklistSubscription::class.java,
                ),
            )

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
    val smsBurstEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_SMS_BURST] ?: true }
    val urlhausStripQueryEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_URLHAUS_STRIP_QUERY] ?: true }
    val urlhausRemoteLookupEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_URLHAUS_REMOTE_LOOKUP] ?: false }
    val contactWhitelistEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_CONTACT_WHITELIST] ?: true }
    val contactsOnlyEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_CONTACTS_ONLY] ?: false }
    val selectedContactGroups: Flow<Set<String>> =
        dataStore.data.map {
            it[SpamRepository.KEY_SELECTED_CONTACT_GROUPS]
                ?.let(ContactGroupCatalog::preserveScope)
                .orEmpty()
        }
    val outgoingRiskWarningEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_OUTGOING_RISK_WARNING] ?: false }
    val regionBlockEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_REGION_BLOCK] ?: false }
    val allowedRegions: Flow<Set<String>> =
        dataStore.data.map { RegionRules.normalizeRegionCodes(it[SpamRepository.KEY_ALLOWED_REGIONS].orEmpty()) }
    val cnapTrustPatterns: Flow<Set<String>> =
        dataStore.data.map { RegionRules.normalizeNamePatterns(it[SpamRepository.KEY_CNAP_TRUST_PATTERNS].orEmpty()) }
    val cnapBlockPatterns: Flow<Set<String>> =
        dataStore.data.map { RegionRules.normalizeNamePatterns(it[SpamRepository.KEY_CNAP_BLOCK_PATTERNS].orEmpty()) }
    val categoryCallActions: Flow<Map<CallCategory, CategoryCallAction>> =
        dataStore.data.map { CategoryCallPolicy.decode(it[SpamRepository.KEY_CATEGORY_CALL_ACTIONS].orEmpty()) }
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
    val emergencyCallbackGraceEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] ?: true }
    val emergencyCallbackWindowMinutes: Flow<Int> =
        dataStore.data.map {
            it[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES]
                ?: CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES
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
    internal val protectionRoleLossNoticeShown: Flow<Boolean> =
        privateDataStore.data.map { it[SpamRepository.KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN] ?: false }
    internal val protectionRoleEverHeld: Flow<Boolean> =
        privateDataStore.data.map { it[SpamRepository.KEY_PROTECTION_ROLE_EVER_HELD] ?: false }
    val autoCleanupEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_AUTO_CLEANUP] ?: false }
    val cleanupDays: Flow<Int> = dataStore.data.map { it[SpamRepository.KEY_CLEANUP_DAYS] ?: DEFAULT_CLEANUP_DAYS }
    val mlScorerEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_ML_SCORER] ?: true }
    val rcsFilterEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_RCS_FILTER] ?: true }
    val postCallScreenEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_POST_CALL_SCREEN] ?: false }
    val notificationScreeningPackages: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            NotificationScreeningSources.enabledPackages(prefs[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES])
        }
    val silentVoicemailEnabled: Flow<Boolean> =
        dataStore.data.map { it[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false }
    val pushAlertEnabled: Flow<Boolean> = dataStore.data.map { it[SpamRepository.KEY_PUSH_ALERT] ?: true }
    val pushAlertDisabledPackages: Flow<Set<String>> =
        dataStore.data.map { it[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet() }
    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it[SpamRepository.KEY_LAST_SYNC] ?: 0L }
    val lastSyncSource: Flow<String> = dataStore.data.map { it[SpamRepository.KEY_LAST_SYNC_SOURCE] ?: "" }
    val activeProfileName: Flow<String?> = dataStore.data.map { it[SpamRepository.KEY_ACTIVE_PROFILE] }
    val appTheme: Flow<String> = dataStore.data.map { sanitizeAppTheme(it[SpamRepository.KEY_APP_THEME]) }
    val externalBlocklistSubscriptions: Flow<List<ExternalBlocklistSubscription>> =
        dataStore.data.map { prefs ->
            decodeExternalBlocklistSubscriptions(prefs[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS])
        }

    suspend fun setActiveProfileName(name: String?) =
        dataStore.edit { prefs ->
            if (name == null) {
                prefs.remove(SpamRepository.KEY_ACTIVE_PROFILE)
            } else {
                prefs[SpamRepository.KEY_ACTIVE_PROFILE] = name
            }
        }

    suspend fun setAppTheme(theme: String) =
        dataStore.edit { preferences ->
            val sanitized = sanitizeAppTheme(theme)
            if (sanitized == "light") {
                preferences.remove(SpamRepository.KEY_APP_THEME)
            } else {
                preferences[SpamRepository.KEY_APP_THEME] = sanitized
            }
        }

    suspend fun setMlScorer(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_ML_SCORER] = enabled }

    suspend fun setRcsFilter(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_RCS_FILTER] = enabled }

    suspend fun setPostCallScreen(enabled: Boolean) =
        dataStore.edit {
            it[SpamRepository.KEY_POST_CALL_SCREEN] = enabled
        }

    suspend fun setNotificationScreeningPackage(
        packageName: String,
        enabled: Boolean,
    ) = dataStore.edit { prefs ->
        if (NotificationScreeningSources.sourceFor(packageName) == null) return@edit
        val current =
            NotificationScreeningSources.enabledPackages(
                prefs[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES],
            )
        prefs[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES] =
            if (enabled) current + packageName else current - packageName
    }

    suspend fun resetNotificationScreeningPackages() = dataStore.edit { it.remove(SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES) }

    suspend fun setNotificationScreeningPackages(packageNames: Set<String>) =
        dataStore.edit { prefs ->
            prefs[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES] =
                packageNames.filterTo(linkedSetOf()) { NotificationScreeningSources.sourceFor(it) != null }
        }

    suspend fun setSilentVoicemail(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_SILENT_VOICEMAIL] = enabled }

    suspend fun setPushAlert(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_PUSH_ALERT] = enabled }

    suspend fun togglePushAlertPackage(
        pkg: String,
        allowed: Boolean,
    ) = dataStore.edit { prefs ->
        val current = prefs[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet()
        val next = if (allowed) current - pkg else current + pkg
        if (next.isEmpty()) {
            prefs.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED)
        } else {
            prefs[SpamRepository.KEY_PUSH_ALERT_DISABLED] = next
        }
    }

    suspend fun resetPushAlertPackages() = dataStore.edit { it.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED) }

    suspend fun setOnboardingDone() = dataStore.edit { it[SpamRepository.KEY_ONBOARDING_DONE] = true }

    internal suspend fun setProtectionRoleLossNoticeShown(shown: Boolean) =
        privateDataStore.edit { preferences ->
            if (shown) {
                preferences[SpamRepository.KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN] = true
            } else {
                preferences.remove(SpamRepository.KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN)
            }
        }

    internal suspend fun setProtectionRoleEverHeld() = privateDataStore.edit { it[SpamRepository.KEY_PROTECTION_ROLE_EVER_HELD] = true }

    suspend fun setAutoCleanup(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_AUTO_CLEANUP] = enabled }

    suspend fun setCleanupDays(days: Int) = dataStore.edit { it[SpamRepository.KEY_CLEANUP_DAYS] = days }

    suspend fun setBlockCalls(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_BLOCK_CALLS] = enabled }

    suspend fun setBlockSms(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_BLOCK_SMS] = enabled }

    suspend fun setBlockUnknown(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_BLOCK_UNKNOWN] = enabled }

    suspend fun setStirShaken(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_STIR_SHAKEN] = enabled }

    suspend fun setStirTrustedAllow(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_STIR_TRUSTED_ALLOW] = enabled }

    suspend fun setAutoMuteLowConfidence(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] = enabled }

    suspend fun setNeighborSpoof(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_NEIGHBOR_SPOOF] = enabled }

    suspend fun setHeuristics(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_HEURISTICS] = enabled }

    suspend fun setSmsContent(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_SMS_CONTENT] = enabled }

    suspend fun setSmsBurst(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_SMS_BURST] = enabled }

    suspend fun setUrlhausStripQuery(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_URLHAUS_STRIP_QUERY] = enabled }

    suspend fun setUrlhausRemoteLookup(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_URLHAUS_REMOTE_LOOKUP] = enabled }

    suspend fun setContactWhitelist(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_CONTACT_WHITELIST] = enabled }

    suspend fun setContactsOnly(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_CONTACTS_ONLY] = enabled }

    suspend fun setSelectedContactGroups(groupKeys: Set<String>) =
        dataStore.edit { preferences ->
            val sanitized = ContactGroupCatalog.sanitizeKeys(groupKeys)
            if (sanitized.isEmpty()) {
                preferences.remove(SpamRepository.KEY_SELECTED_CONTACT_GROUPS)
            } else {
                preferences[SpamRepository.KEY_SELECTED_CONTACT_GROUPS] = sanitized
            }
        }

    suspend fun setOutgoingRiskWarning(enabled: Boolean) =
        dataStore.edit {
            it[SpamRepository.KEY_OUTGOING_RISK_WARNING] = enabled
        }

    suspend fun setRegionBlock(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_REGION_BLOCK] = enabled }

    suspend fun setAllowedRegions(regions: Set<String>) =
        dataStore.edit { prefs ->
            val normalized = RegionRules.normalizeRegionCodes(regions)
            if (normalized.isEmpty()) {
                prefs.remove(SpamRepository.KEY_ALLOWED_REGIONS)
                prefs[SpamRepository.KEY_REGION_BLOCK] = false
            } else {
                prefs[SpamRepository.KEY_ALLOWED_REGIONS] = normalized
            }
        }

    suspend fun setCnapTrustPatterns(patterns: Set<String>) =
        dataStore.edit { prefs ->
            val normalized = RegionRules.normalizeNamePatterns(patterns)
            if (normalized.isEmpty()) {
                prefs.remove(SpamRepository.KEY_CNAP_TRUST_PATTERNS)
            } else {
                prefs[SpamRepository.KEY_CNAP_TRUST_PATTERNS] = normalized
            }
        }

    suspend fun setCnapBlockPatterns(patterns: Set<String>) =
        dataStore.edit { prefs ->
            val normalized = RegionRules.normalizeNamePatterns(patterns)
            if (normalized.isEmpty()) {
                prefs.remove(SpamRepository.KEY_CNAP_BLOCK_PATTERNS)
            } else {
                prefs[SpamRepository.KEY_CNAP_BLOCK_PATTERNS] = normalized
            }
        }

    suspend fun setCategoryCallAction(
        category: CallCategory,
        action: CategoryCallAction,
    ) = dataStore.edit { preferences ->
        val updated =
            CategoryCallPolicy.update(
                serialized = preferences[SpamRepository.KEY_CATEGORY_CALL_ACTIONS].orEmpty(),
                category = category,
                action = action,
            )
        if (updated.isEmpty()) {
            preferences.remove(SpamRepository.KEY_CATEGORY_CALL_ACTIONS)
        } else {
            preferences[SpamRepository.KEY_CATEGORY_CALL_ACTIONS] = updated
        }
    }

    suspend fun setDbPrefixExpansion(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_DB_PREFIX_EXPANSION] = enabled }

    suspend fun setAggressiveMode(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_AGGRESSIVE_MODE] = enabled }

    suspend fun setAnsweredCallerTrust(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_ANSWERED_CALLER_TRUST] = enabled }

    suspend fun setAnsweredCallerThreshold(threshold: Int) =
        dataStore.edit {
            it[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD] =
                threshold.coerceIn(
                    ANSWERED_CALLER_THRESHOLD_MIN,
                    ANSWERED_CALLER_THRESHOLD_MAX,
                )
        }

    suspend fun setAnsweredCallerWindowDays(days: Int) =
        dataStore.edit {
            it[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS] =
                days.coerceIn(
                    ANSWERED_CALLER_WINDOW_DAYS_MIN,
                    ANSWERED_CALLER_WINDOW_DAYS_MAX,
                )
        }

    suspend fun setEmergencyCallbackGrace(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] = enabled }

    suspend fun setEmergencyCallbackWindowMinutes(minutes: Int) =
        dataStore.edit {
            it[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES] =
                minutes.coerceIn(
                    EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN,
                    EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX,
                )
        }

    suspend fun setTimeBlock(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK] = enabled }

    suspend fun setTimeBlockStart(hour: Int) = dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK_START] = hour }

    suspend fun setTimeBlockEnd(hour: Int) = dataStore.edit { it[SpamRepository.KEY_TIME_BLOCK_END] = hour }

    suspend fun setFreqEscalation(enabled: Boolean) = dataStore.edit { it[SpamRepository.KEY_FREQ_ESCALATION] = enabled }

    suspend fun setFreqThreshold(threshold: Int) = dataStore.edit { it[SpamRepository.KEY_FREQ_THRESHOLD] = threshold.coerceIn(1, 25) }

    suspend fun readPrefsSnapshot(): Preferences = dataStore.data.first()

    internal suspend fun editPreferences(transform: suspend (MutablePreferences) -> Unit) {
        dataStore.edit(transform)
    }

    suspend fun readLastDataSha(): String? = dataStore.data.first()[SpamRepository.KEY_LAST_SHA]

    suspend fun readExternalBlocklistSubscriptions(): List<ExternalBlocklistSubscription> =
        decodeExternalBlocklistSubscriptions(
            dataStore.data.first()[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS],
        )

    suspend fun saveExternalBlocklistSubscriptions(subscriptions: List<ExternalBlocklistSubscription>) =
        dataStore.edit { prefs ->
            if (subscriptions.isEmpty()) {
                prefs.remove(SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS)
            } else {
                prefs[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS] =
                    externalBlocklistAdapter.toJson(subscriptions.sortedBy { it.label.lowercase() })
            }
        }

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

    /**
     * One-time cleanup of the retired optional AbstractAPI key. Earlier builds let users
     * paste a carrier-enrichment key; that entry has been removed (the app is fully free and
     * keyless), so purge any residual value from both the public and private no-backup stores.
     */
    suspend fun purgeLegacyAbstractApiKey() {
        if (privateDataStore.data.first()[SpamRepository.KEY_ABSTRACT_API_KEY] != null) {
            privateDataStore.edit { it.remove(SpamRepository.KEY_ABSTRACT_API_KEY) }
        }
        if (dataStore.data.first()[SpamRepository.KEY_ABSTRACT_API_KEY] != null) {
            dataStore.edit { it.remove(SpamRepository.KEY_ABSTRACT_API_KEY) }
        }
    }

    private fun decodeExternalBlocklistSubscriptions(raw: String?): List<ExternalBlocklistSubscription> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { externalBlocklistAdapter.fromJson(raw).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.url.isNotBlank() }
    }
}

internal const val ANSWERED_CALLER_THRESHOLD_MIN = 1
internal const val ANSWERED_CALLER_THRESHOLD_MAX = 10
internal const val FREQ_THRESHOLD_MIN = 1
internal const val FREQ_THRESHOLD_MAX = 25
internal const val ANSWERED_CALLER_WINDOW_DAYS_MIN = 1
internal const val ANSWERED_CALLER_WINDOW_DAYS_MAX = 365
internal const val EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN = 15
internal const val EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX = 360
