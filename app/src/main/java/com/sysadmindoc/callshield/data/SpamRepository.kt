package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.sysadmindoc.callshield.data.checker.BlockResult
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.*
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.data.repository.BlocklistRepository
import com.sysadmindoc.callshield.data.repository.SettingsRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryImpl
import com.sysadmindoc.callshield.data.repository.SyncRepository
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.ScreeningDiagnostics
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.model.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal fun replaceCorruptPreferences() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "callshield_prefs",
    corruptionHandler = replaceCorruptPreferences(),
)

private object NoBackupPreferenceStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(
        context: Context,
        name: String,
    ): DataStore<Preferences> {
        val appContext = context.applicationContext
        val key = "${appContext.noBackupFilesDir.absolutePath}/$name"
        return stores.computeIfAbsent(key) {
            PreferenceDataStoreFactory.create(
                corruptionHandler = replaceCorruptPreferences(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    File(appContext.noBackupFilesDir, "datastore").apply { mkdirs() }
                    File(File(appContext.noBackupFilesDir, "datastore"), "$name.preferences_pb")
                },
            )
        }
    }
}

private fun Context.noBackupDataStore(name: String): DataStore<Preferences> = NoBackupPreferenceStores.get(this, name)

@Suppress("TooManyFunctions", "LongParameterList")
class SpamRepository(
    context: Context,
    database: AppDatabase = AppDatabase.getInstance(context),
    remote: SpamDataSource = GitHubDataSource(),
    checkerDependencies: CheckerDependencies = CheckerDependencies(),
    settingsDataStore: DataStore<Preferences>? = null,
    privateSettingsDataStore: DataStore<Preferences>? = null,
    private val phoneIdentityCanonicalizer: PhoneIdentityCanonicalizer =
        PhoneIdentityCanonicalizer.fromContext(context.applicationContext),
) {
    private val appContext: Context = context.applicationContext
    private val db: AppDatabase = database
    private val dao: SpamDao = database.spamDao()
    private val settingsRepository =
        SettingsRepository(
            dataStore = settingsDataStore ?: appContext.dataStore,
            privateDataStore =
                privateSettingsDataStore ?: appContext.noBackupDataStore("callshield_private_prefs"),
        )
    private val spamRepositoryImpl =
        SpamRepositoryImpl(
            context = appContext,
            dao = dao,
            settingsRepository = settingsRepository,
            checkerDependencies = checkerDependencies,
            normalizePhone = phoneIdentityCanonicalizer::canonicalizePhone,
            normalizeSenderIdentity = phoneIdentityCanonicalizer::canonicalizeIdentity,
            senderRegionIso = phoneIdentityCanonicalizer.homeRegionIso,
        )
    private val syncRepository =
        SyncRepository(
            context = appContext,
            dao = dao,
            remote = remote,
            settingsRepository = settingsRepository,
            normalizeNumber = phoneIdentityCanonicalizer::canonicalizePhone,
            invalidateAllCaches = spamRepositoryImpl::invalidateAllCaches,
        )
    private val blocklistRepository =
        BlocklistRepository(
            context = appContext,
            dao = dao,
            settingsRepository = settingsRepository,
            normalizeNumber = phoneIdentityCanonicalizer::canonicalizePhone,
            normalizeLogIdentity = phoneIdentityCanonicalizer::canonicalizeIdentity,
            invalidateWildcardCache = spamRepositoryImpl::invalidateWildcardCache,
            invalidateKeywordCache = spamRepositoryImpl::invalidateKeywordCache,
            invalidateHashWildcardCache = spamRepositoryImpl::invalidateHashWildcardCache,
            runInTransaction = { block -> db.withTransaction { block() } },
        )

    companion object {
        internal val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        internal val KEY_LAST_SYNC_SOURCE = stringPreferencesKey("last_sync_source")
        internal val KEY_LAST_SHA = stringPreferencesKey("last_data_sha")
        internal val KEY_LAST_SHARD_HASHES = stringPreferencesKey("last_data_shard_hashes")
        internal val KEY_DB_VERSION = intPreferencesKey("db_version")
        internal val KEY_HOT_DATA_LAST_GOOD = longPreferencesKey("hot_data_last_good_timestamp")
        internal val KEY_HOT_DATA_UNAVAILABLE = stringSetPreferencesKey("hot_data_unavailable_feeds")
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
        val KEY_SMS_BURST = booleanPreferencesKey("sms_burst_detection_enabled")
        val KEY_URLHAUS_STRIP_QUERY = booleanPreferencesKey("urlhaus_strip_query_enabled")
        val KEY_URLHAUS_REMOTE_LOOKUP = booleanPreferencesKey("urlhaus_remote_lookup_enabled")
        val KEY_LIVE_CALLER_ENRICHMENT = booleanPreferencesKey("live_caller_enrichment_enabled")
        val KEY_CONTACT_WHITELIST = booleanPreferencesKey("contact_whitelist_enabled")
        val KEY_CONTACTS_ONLY = booleanPreferencesKey("contacts_only_mode_enabled")
        val KEY_SELECTED_CONTACT_GROUPS = stringSetPreferencesKey("selected_contact_group_keys")
        val KEY_OUTGOING_RISK_WARNING = booleanPreferencesKey("outgoing_risk_warning_enabled")
        val KEY_REGION_BLOCK = booleanPreferencesKey("region_block_enabled")
        val KEY_ALLOWED_REGIONS = stringSetPreferencesKey("allowed_call_regions")
        val KEY_CNAP_TRUST_PATTERNS = stringSetPreferencesKey("cnap_trust_patterns")
        val KEY_CNAP_BLOCK_PATTERNS = stringSetPreferencesKey("cnap_block_patterns")
        val KEY_CATEGORY_CALL_ACTIONS = stringSetPreferencesKey("category_call_actions")
        val KEY_DB_PREFIX_EXPANSION = booleanPreferencesKey("db_prefix_expansion_enabled")
        val KEY_AGGRESSIVE_MODE = booleanPreferencesKey("aggressive_mode_enabled")
        val KEY_ANSWERED_CALLER_TRUST = booleanPreferencesKey("answered_caller_trust_enabled")
        val KEY_ANSWERED_CALLER_THRESHOLD = intPreferencesKey("answered_caller_trust_threshold")
        val KEY_ANSWERED_CALLER_WINDOW_DAYS = intPreferencesKey("answered_caller_trust_window_days")
        val KEY_EMERGENCY_CALLBACK_GRACE = booleanPreferencesKey("emergency_callback_grace_enabled")
        val KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES = intPreferencesKey("emergency_callback_grace_window_minutes")

        // Feature 9: Time-based blocking
        val KEY_TIME_BLOCK = booleanPreferencesKey("time_block_enabled")
        val KEY_TIME_BLOCK_START = intPreferencesKey("time_block_start_hour") // 0-23
        val KEY_TIME_BLOCK_END = intPreferencesKey("time_block_end_hour")

        // Feature 10: Frequency auto-escalation
        val KEY_FREQ_ESCALATION = booleanPreferencesKey("freq_escalation_enabled")
        val KEY_FREQ_THRESHOLD = intPreferencesKey("freq_threshold")
        internal val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        internal val KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN =
            booleanPreferencesKey("protection_role_loss_notice_shown")
        internal val KEY_PROTECTION_ROLE_EVER_HELD =
            booleanPreferencesKey("protection_role_ever_held")
        internal val KEY_AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup_enabled")
        internal val KEY_CLEANUP_DAYS = intPreferencesKey("cleanup_retention_days")
        internal val KEY_ABSTRACT_API_KEY = stringPreferencesKey("abstract_api_key")
        internal val KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS =
            stringPreferencesKey("external_blocklist_subscriptions")
        val KEY_ML_SCORER = booleanPreferencesKey("ml_scorer_enabled")
        val KEY_RCS_FILTER = booleanPreferencesKey("rcs_filter_enabled")
        val KEY_POST_CALL_SCREEN = booleanPreferencesKey("post_call_screen_enabled")
        val KEY_NOTIFICATION_SCREENING_PACKAGES = stringSetPreferencesKey("notification_screening_packages")

        // Privacy-safe message-ingress health. These values contain only the
        // latest capability state, API level, and timing metadata — never a
        // sender, message body, URL, or notification package.
        internal val KEY_SMS_CAPABILITY_STATE = stringPreferencesKey("sms_capability_state")
        internal val KEY_SMS_CAPABILITY_API = intPreferencesKey("sms_capability_api")
        internal val KEY_SMS_CAPABILITY_OBSERVED_AT = longPreferencesKey("sms_capability_observed_at")
        internal val KEY_SMS_CAPABILITY_LATENCY = longPreferencesKey("sms_capability_latency")
        internal val KEY_NOTIFICATION_CAPABILITY_STATE = stringPreferencesKey("notification_capability_state")
        internal val KEY_NOTIFICATION_CAPABILITY_API = intPreferencesKey("notification_capability_api")
        internal val KEY_NOTIFICATION_CAPABILITY_OBSERVED_AT = longPreferencesKey("notification_capability_observed_at")
        internal val KEY_NOTIFICATION_CAPABILITY_LATENCY = longPreferencesKey("notification_capability_latency")

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

        // Last-applied blocking profile name (BlockingProfiles.Profile.name). Persisted so the
        // Dashboard chip-row reflects the user's choice across process death / config change.
        // Without this key, _activeProfile defaulted back to null on every VM init, so the
        // Maximum profile (and every other profile) would visually "reset" — issue #2.
        val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_profile_name")
        internal val KEY_APP_THEME = stringPreferencesKey("app_theme")

        /** SharedPreferences key for the synchronous theme mirror (cold-start flash fix). */
        private const val KEY_THEME_CACHE = "app_theme"
        private const val KEY_THEME_CACHE_SCHEMA = "app_theme_schema"
        private const val KEY_POST_CALL_CACHE = "post_call_screen_enabled"
        private const val THEME_CACHE_SCHEMA = 3

        /**
         * Static variant of [cachedAppTheme] for Activities that must resolve
         * the theme BEFORE super.onCreate (window-background selection) without
         * constructing the full repository singleton on the main thread.
         */
        fun cachedAppTheme(context: Context): String {
            val cache =
                context.applicationContext
                    .getSharedPreferences("theme_cache", Context.MODE_PRIVATE)
            val cached = cache.getString(KEY_THEME_CACHE, null)
            return if (cache.getInt(KEY_THEME_CACHE_SCHEMA, 1) >= THEME_CACHE_SCHEMA) {
                cached ?: "light"
            } else {
                // In schema 1, AMOLED was both the implicit default and the
                // stored mirror. Treat only that legacy value as the new Light
                // default; explicit Light/Graphite selections stay intact.
                cached?.takeUnless { it == "amoled" } ?: "light"
            }
        }

        /** Null until the asynchronous preference has been mirrored at least once. */
        fun cachedPostCallScreenEnabled(context: Context): Boolean? {
            val cache =
                context.applicationContext
                    .getSharedPreferences("theme_cache", Context.MODE_PRIVATE)
            return if (cache.contains(KEY_POST_CALL_CACHE)) {
                cache.getBoolean(KEY_POST_CALL_CACHE, false)
            } else {
                null
            }
        }

        fun cachePostCallScreenEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            context.applicationContext
                .getSharedPreferences("theme_cache", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_POST_CALL_CACHE, enabled)
                .apply()
        }

        const val SYNC_SOURCE_REMOTE = "remote"
        const val SYNC_SOURCE_BUNDLED = "bundled"

        @Volatile
        private var instance: SpamRepository? = null

        fun getInstance(
            context: Context,
            database: AppDatabase = AppDatabase.getInstance(context),
            remote: SpamDataSource = GitHubDataSource(),
            checkerDependencies: CheckerDependencies = CheckerDependencies(),
        ): SpamRepository =
            instance ?: synchronized(this) {
                instance ?: SpamRepository(
                    context = context.applicationContext,
                    database = database,
                    remote = remote,
                    checkerDependencies = checkerDependencies,
                ).also { instance = it }
            }
    }

    // Settings
    val blockCallsEnabled: Flow<Boolean> = settingsRepository.blockCallsEnabled
    val blockSmsEnabled: Flow<Boolean> = settingsRepository.blockSmsEnabled
    val blockUnknownEnabled: Flow<Boolean> = settingsRepository.blockUnknownEnabled
    val stirShakenEnabled: Flow<Boolean> = settingsRepository.stirShakenEnabled
    val stirTrustedAllowEnabled: Flow<Boolean> = settingsRepository.stirTrustedAllowEnabled
    val autoMuteLowConfidenceEnabled: Flow<Boolean> = settingsRepository.autoMuteLowConfidenceEnabled
    val neighborSpoofEnabled: Flow<Boolean> = settingsRepository.neighborSpoofEnabled
    val heuristicsEnabled: Flow<Boolean> = settingsRepository.heuristicsEnabled
    val smsContentEnabled: Flow<Boolean> = settingsRepository.smsContentEnabled
    val smsBurstEnabled: Flow<Boolean> = settingsRepository.smsBurstEnabled
    val urlhausStripQueryEnabled: Flow<Boolean> = settingsRepository.urlhausStripQueryEnabled
    val urlhausRemoteLookupEnabled: Flow<Boolean> = settingsRepository.urlhausRemoteLookupEnabled
    val liveCallerEnrichmentEnabled: Flow<Boolean> = settingsRepository.liveCallerEnrichmentEnabled
    val contactWhitelistEnabled: Flow<Boolean> = settingsRepository.contactWhitelistEnabled
    val contactsOnlyEnabled: Flow<Boolean> = settingsRepository.contactsOnlyEnabled
    val selectedContactGroups: Flow<Set<String>> = settingsRepository.selectedContactGroups
    val outgoingRiskWarningEnabled: Flow<Boolean> = settingsRepository.outgoingRiskWarningEnabled
    val regionBlockEnabled: Flow<Boolean> = settingsRepository.regionBlockEnabled
    val allowedRegions: Flow<Set<String>> = settingsRepository.allowedRegions
    val cnapTrustPatterns: Flow<Set<String>> = settingsRepository.cnapTrustPatterns
    val cnapBlockPatterns: Flow<Set<String>> = settingsRepository.cnapBlockPatterns
    val categoryCallActions: Flow<Map<CallCategory, CategoryCallAction>> = settingsRepository.categoryCallActions
    val dbPrefixExpansionEnabled: Flow<Boolean> = settingsRepository.dbPrefixExpansionEnabled
    val aggressiveModeEnabled: Flow<Boolean> = settingsRepository.aggressiveModeEnabled
    val answeredCallerTrustEnabled: Flow<Boolean> = settingsRepository.answeredCallerTrustEnabled
    val answeredCallerThreshold: Flow<Int> = settingsRepository.answeredCallerThreshold
    val answeredCallerWindowDays: Flow<Int> = settingsRepository.answeredCallerWindowDays
    val emergencyCallbackGraceEnabled: Flow<Boolean> = settingsRepository.emergencyCallbackGraceEnabled
    val emergencyCallbackWindowMinutes: Flow<Int> = settingsRepository.emergencyCallbackWindowMinutes
    val timeBlockEnabled: Flow<Boolean> = settingsRepository.timeBlockEnabled
    val timeBlockStart: Flow<Int> = settingsRepository.timeBlockStart
    val timeBlockEnd: Flow<Int> = settingsRepository.timeBlockEnd
    val freqEscalationEnabled: Flow<Boolean> = settingsRepository.freqEscalationEnabled
    val freqThreshold: Flow<Int> = settingsRepository.freqThreshold
    val onboardingDone: Flow<Boolean> = settingsRepository.onboardingDone
    internal val protectionRoleLossNoticeShown: Flow<Boolean> =
        settingsRepository.protectionRoleLossNoticeShown
    internal val protectionRoleEverHeld: Flow<Boolean> =
        settingsRepository.protectionRoleEverHeld
    val autoCleanupEnabled: Flow<Boolean> = settingsRepository.autoCleanupEnabled
    val cleanupDays: Flow<Int> = settingsRepository.cleanupDays
    val mlScorerEnabled: Flow<Boolean> = settingsRepository.mlScorerEnabled
    val rcsFilterEnabled: Flow<Boolean> = settingsRepository.rcsFilterEnabled
    val postCallScreenEnabled: Flow<Boolean> = settingsRepository.postCallScreenEnabled
    val notificationScreeningPackages: Flow<Set<String>> = settingsRepository.notificationScreeningPackages
    val silentVoicemailEnabled: Flow<Boolean> = settingsRepository.silentVoicemailEnabled
    val pushAlertEnabled: Flow<Boolean> = settingsRepository.pushAlertEnabled
    val pushAlertDisabledPackages: Flow<Set<String>> = settingsRepository.pushAlertDisabledPackages
    internal val smsMessageCapabilityStatus: Flow<MessageCapabilityStatus> = settingsRepository.smsMessageCapabilityStatus
    internal val notificationMessageCapabilityStatus: Flow<MessageCapabilityStatus> =
        settingsRepository.notificationMessageCapabilityStatus
    val lastSyncTimestamp: Flow<Long> = settingsRepository.lastSyncTimestamp
    val lastSyncSource: Flow<String> = settingsRepository.lastSyncSource
    val activeProfileName: Flow<String?> = settingsRepository.activeProfileName
    val appTheme: Flow<String> = settingsRepository.appTheme
    val externalBlocklistSubscriptions = settingsRepository.externalBlocklistSubscriptions

    suspend fun setActiveProfileName(name: String?) = settingsRepository.setActiveProfileName(name)

    /** Persist and log only message-ingress capability metadata; never message content. */
    internal suspend fun recordMessageCapability(status: MessageCapabilityStatus) {
        try {
            settingsRepository.recordMessageCapability(status)
            android.util.Log.i("CallShieldCapability", status.privacySafeLogLine())
        } catch (exception: Exception) {
            // Capability health is advisory. A locked/corrupt settings store
            // must never prevent sender or URL screening from running.
            android.util.Log.w("CallShieldCapability", "Capability state unavailable", exception)
        }
    }

    // Synchronous mirror of the persisted theme. DataStore is async-only, so its
    // first emission arrives a frame or two after the Activity starts, flashing the
    // System-theme fallback with a synchronous cache prevents a mismatched window
    // every cold start. A tiny SharedPreferences mirror lets the ViewModel seed the
    // theme StateFlow synchronously at construction and eliminate that flash.
    private val themeCache: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences("theme_cache", Context.MODE_PRIVATE)
    }

    /** Last-known theme, read synchronously. Defaults to the system-following theme. */
    fun cachedAppTheme(): String {
        val cached = themeCache.getString(KEY_THEME_CACHE, null)
        return if (themeCache.getInt(KEY_THEME_CACHE_SCHEMA, 1) >= THEME_CACHE_SCHEMA) {
            cached ?: "light"
        } else {
            cached?.takeUnless { it == "amoled" } ?: "light"
        }
    }

    /** Update the synchronous theme mirror (called on writes and backfilled at start). */
    fun cacheAppTheme(theme: String) {
        themeCache
            .edit()
            .putString(KEY_THEME_CACHE, theme)
            .putInt(KEY_THEME_CACHE_SCHEMA, THEME_CACHE_SCHEMA)
            .apply()
    }

    fun cachePostCallScreenEnabled(enabled: Boolean) {
        cachePostCallScreenEnabled(appContext, enabled)
    }

    suspend fun setAppTheme(theme: String) {
        settingsRepository.setAppTheme(theme)
        cacheAppTheme(theme)
    }

    suspend fun purgeLegacyAbstractApiKey() = settingsRepository.purgeLegacyAbstractApiKey()

    suspend fun setMlScorer(enabled: Boolean) = settingsRepository.setMlScorer(enabled)

    suspend fun setRcsFilter(enabled: Boolean) = settingsRepository.setRcsFilter(enabled)

    suspend fun setPostCallScreen(enabled: Boolean) {
        settingsRepository.setPostCallScreen(enabled)
        cachePostCallScreenEnabled(enabled)
    }

    suspend fun setNotificationScreeningPackage(
        packageName: String,
        enabled: Boolean,
    ) = settingsRepository.setNotificationScreeningPackage(packageName, enabled)

    suspend fun resetNotificationScreeningPackages() = settingsRepository.resetNotificationScreeningPackages()

    suspend fun setNotificationScreeningPackages(packageNames: Set<String>) {
        settingsRepository.setNotificationScreeningPackages(packageNames)
    }

    suspend fun setSilentVoicemail(enabled: Boolean) = settingsRepository.setSilentVoicemail(enabled)

    suspend fun setPushAlert(enabled: Boolean) = settingsRepository.setPushAlert(enabled)

    suspend fun togglePushAlertPackage(
        pkg: String,
        allowed: Boolean,
    ) = settingsRepository.togglePushAlertPackage(pkg, allowed)

    suspend fun resetPushAlertPackages() = settingsRepository.resetPushAlertPackages()

    suspend fun setOnboardingDone() = settingsRepository.setOnboardingDone()

    internal suspend fun setProtectionRoleLossNoticeShown(shown: Boolean) = settingsRepository.setProtectionRoleLossNoticeShown(shown)

    internal suspend fun setProtectionRoleEverHeld() = settingsRepository.setProtectionRoleEverHeld()

    suspend fun setAutoCleanup(enabled: Boolean) = settingsRepository.setAutoCleanup(enabled)

    suspend fun setCleanupDays(days: Int) = settingsRepository.setCleanupDays(days)

    suspend fun setBlockCalls(enabled: Boolean) = settingsRepository.setBlockCalls(enabled)

    suspend fun setBlockSms(enabled: Boolean) = settingsRepository.setBlockSms(enabled)

    suspend fun setBlockUnknown(enabled: Boolean) = settingsRepository.setBlockUnknown(enabled)

    suspend fun setStirShaken(enabled: Boolean) = settingsRepository.setStirShaken(enabled)

    suspend fun setStirTrustedAllow(enabled: Boolean) = settingsRepository.setStirTrustedAllow(enabled)

    suspend fun setAutoMuteLowConfidence(enabled: Boolean) = settingsRepository.setAutoMuteLowConfidence(enabled)

    suspend fun setNeighborSpoof(enabled: Boolean) = settingsRepository.setNeighborSpoof(enabled)

    suspend fun setHeuristics(enabled: Boolean) = settingsRepository.setHeuristics(enabled)

    suspend fun setSmsContent(enabled: Boolean) = settingsRepository.setSmsContent(enabled)

    suspend fun setSmsBurst(enabled: Boolean) = settingsRepository.setSmsBurst(enabled)

    suspend fun setUrlhausStripQuery(enabled: Boolean) = settingsRepository.setUrlhausStripQuery(enabled)

    suspend fun setUrlhausRemoteLookup(enabled: Boolean) = settingsRepository.setUrlhausRemoteLookup(enabled)

    suspend fun setLiveCallerEnrichment(enabled: Boolean) = settingsRepository.setLiveCallerEnrichment(enabled)

    suspend fun setContactWhitelist(enabled: Boolean) = settingsRepository.setContactWhitelist(enabled)

    suspend fun setContactsOnly(enabled: Boolean) = settingsRepository.setContactsOnly(enabled)

    suspend fun setSelectedContactGroups(groupKeys: Set<String>) = settingsRepository.setSelectedContactGroups(groupKeys)

    suspend fun setOutgoingRiskWarning(enabled: Boolean) = settingsRepository.setOutgoingRiskWarning(enabled)

    suspend fun setRegionBlock(enabled: Boolean) = settingsRepository.setRegionBlock(enabled)

    suspend fun setAllowedRegions(regions: Set<String>) = settingsRepository.setAllowedRegions(regions)

    suspend fun setCnapTrustPatterns(patterns: Set<String>) = settingsRepository.setCnapTrustPatterns(patterns)

    suspend fun setCnapBlockPatterns(patterns: Set<String>) = settingsRepository.setCnapBlockPatterns(patterns)

    suspend fun setCategoryCallAction(
        category: CallCategory,
        action: CategoryCallAction,
    ) = settingsRepository.setCategoryCallAction(category, action)

    suspend fun setDbPrefixExpansion(enabled: Boolean) = settingsRepository.setDbPrefixExpansion(enabled)

    suspend fun setAggressiveMode(enabled: Boolean) = settingsRepository.setAggressiveMode(enabled)

    suspend fun setAnsweredCallerTrust(enabled: Boolean) = settingsRepository.setAnsweredCallerTrust(enabled)

    suspend fun setAnsweredCallerThreshold(threshold: Int) = settingsRepository.setAnsweredCallerThreshold(threshold)

    suspend fun setAnsweredCallerWindowDays(days: Int) = settingsRepository.setAnsweredCallerWindowDays(days)

    suspend fun setEmergencyCallbackGrace(enabled: Boolean) = settingsRepository.setEmergencyCallbackGrace(enabled)

    suspend fun setEmergencyCallbackWindowMinutes(minutes: Int) = settingsRepository.setEmergencyCallbackWindowMinutes(minutes)

    suspend fun setTimeBlock(enabled: Boolean) = settingsRepository.setTimeBlock(enabled)

    suspend fun setTimeBlockStart(hour: Int) = settingsRepository.setTimeBlockStart(hour)

    suspend fun setTimeBlockEnd(hour: Int) = settingsRepository.setTimeBlockEnd(hour)

    suspend fun setFreqEscalation(enabled: Boolean) = settingsRepository.setFreqEscalation(enabled)

    suspend fun setFreqThreshold(threshold: Int) = settingsRepository.setFreqThreshold(threshold)

    /**
     * Read the full preferences snapshot once. Use this from hot paths
     * (CallScreeningService, SmsReceiver) that need several settings at
     * once — calling [Flow.first] per key regresses the 5-second deadline.
     */
    suspend fun readPrefsSnapshot(): Preferences = settingsRepository.readPrefsSnapshot()

    internal suspend fun findExactSpamNumber(normalized: String) = spamRepositoryImpl.findByNumberInternal(normalized)

    internal suspend fun hasActiveWhitelistEntry(normalized: String): Boolean =
        spamRepositoryImpl.findWhitelistEntryInternal(normalized) != null ||
            spamRepositoryImpl.findTemporaryWhitelistEntryInternal(normalized) != null

    internal suspend fun editPreferences(transform: suspend (MutablePreferences) -> Unit) {
        settingsRepository.editPreferences(transform)
    }

    // ── Primary spam check ─────────────────────────────────────────────

    /**
     * @param realtimeCall `true` for live incoming calls/SMS (the default) —
     *   feeds `CampaignDetector` and may surface the suspicious-caller overlay.
     *   Pass `false` from the historical call-log / SMS-inbox scanners so they
     *   don't poison the bounded local campaign detector with old numbers (any
     *   5+ historical unknowns sharing an NPA-NXX would otherwise flag that
     *   prefix as an active campaign for the next hour) and don't pop overlays for
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
        callerIdentity: CallerIdentity? = null,
    ): SpamCheckResult =
        spamRepositoryImpl.isSpam(
            number = number,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
            callerIdentity = callerIdentity,
        )

    // ── SMS-specific check ─────────────────────────────────────────────

    /** @param realtimeCall see [isSpam] — pass `false` from historical scanners. */
    suspend fun isSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult =
        spamRepositoryImpl.isSpamSms(
            number = number,
            body = body,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
        )

    // ── Pipeline trace (diagnostic) ─────────────────────────────────────
    suspend fun traceRules(number: String): com.sysadmindoc.callshield.data.checker.PipelineTrace = spamRepositoryImpl.traceRules(number)

    // ── Sync ───────────────────────────────────────────────────────────

    /**
     * @param force When true, skips the SHA check and always downloads.
     *              Used for manual sync to guarantee fresh data.
     */
    suspend fun syncFromGitHub(force: Boolean = false): SyncResult = syncRepository.syncFromGitHub(force)

    suspend fun previewExternalBlocklistSubscription(
        url: String,
        label: String = "",
    ) = syncRepository.previewExternalBlocklistSubscription(url, label)

    suspend fun applyExternalBlocklistSubscription(
        url: String,
        label: String = "",
    ) = syncRepository.applyExternalBlocklistSubscription(url, label)

    suspend fun setExternalBlocklistSubscriptionEnabled(
        id: String,
        enabled: Boolean,
    ) = syncRepository.setExternalBlocklistSubscriptionEnabled(id, enabled)

    suspend fun removeExternalBlocklistSubscription(id: String) = syncRepository.removeExternalBlocklistSubscription(id)

    // ── Blocklist management ───────────────────────────────────────────
    suspend fun blockNumber(
        number: String,
        type: String = "unknown",
        description: String = "",
        cleanupExpired: Boolean = true,
    ) = blocklistRepository.blockNumber(number, type, description, cleanupExpired = cleanupExpired)

    suspend fun temporaryBlockNumber(
        number: String,
        expiresAt: Long,
        type: String = "unknown",
        description: String = "",
    ) = blocklistRepository.temporaryBlockNumber(number, expiresAt, type, description)

    suspend fun cleanupExpiredTemporaryDecisions() = blocklistRepository.cleanupExpiredTemporaryDecisions()

    suspend fun temporaryAllowNumber(
        number: String,
        expiresAt: Long,
        description: String = "",
    ) = blocklistRepository.temporaryAllowNumber(number, expiresAt, description)

    suspend fun unblockNumber(number: SpamNumber) = blocklistRepository.unblockNumber(number)

    suspend fun restoreBlockedNumber(number: SpamNumber) = blocklistRepository.restoreBlockedNumber(number)

    suspend fun unblockByNumber(number: String) = blocklistRepository.unblockByNumber(number)

    // ── Wildcard rules (Feature 8) ─────────────────────────────────────
    fun getAllWildcardRules(): Flow<List<WildcardRule>> = blocklistRepository.getAllWildcardRules()

    suspend fun addWildcardRule(
        pattern: String,
        isRegex: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) = blocklistRepository.addWildcardRule(pattern, isRegex, description, schedule)

    suspend fun deleteWildcardRule(rule: WildcardRule) = blocklistRepository.deleteWildcardRule(rule)

    suspend fun toggleWildcardRule(
        id: Long,
        enabled: Boolean,
    ) = blocklistRepository.toggleWildcardRule(id, enabled)

    // ── Hash wildcard rules (A5, length-locked `#` patterns) ───────────
    fun getAllHashWildcardRules(): Flow<List<HashWildcardRule>> = blocklistRepository.getAllHashWildcardRules()

    suspend fun addHashWildcardRule(
        pattern: String,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ): Boolean = blocklistRepository.addHashWildcardRule(pattern, description, schedule)

    suspend fun deleteHashWildcardRule(rule: HashWildcardRule) = blocklistRepository.deleteHashWildcardRule(rule)

    suspend fun toggleHashWildcardRule(
        id: Long,
        enabled: Boolean,
    ) = blocklistRepository.toggleHashWildcardRule(id, enabled)

    // ── Call log ───────────────────────────────────────────────────────
    @Suppress("LongParameterList")
    suspend fun logBlockedCall(
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        logKey: String? = null,
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
        origid: String? = null,
    ) = blocklistRepository.logBlockedCall(
        number = number,
        isCall = isCall,
        smsBody = smsBody,
        matchReason = matchReason,
        confidence = confidence,
        timestamp = timestamp,
        logKey = logKey,
        ruleId = ruleId,
        pipelineDiagnostic = pipelineDiagnostic,
        origid = origid,
    )

    @Suppress("LongParameterList")
    suspend fun logScreeningExemption(
        number: String,
        isCall: Boolean = false,
        smsBody: String? = null,
        matchReason: String,
        type: String = "safety_floor",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
    ) = blocklistRepository.logScreeningExemption(
        number = number,
        isCall = isCall,
        smsBody = smsBody,
        matchReason = matchReason,
        type = type,
        confidence = confidence,
        timestamp = timestamp,
        ruleId = ruleId,
        pipelineDiagnostic = pipelineDiagnostic,
    )

    suspend fun logScreeningDiagnostic(
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        pipelineDiagnostic: String,
        timestamp: Long = System.currentTimeMillis(),
    ) = blocklistRepository.logScreeningDiagnostic(
        number = number,
        isCall = isCall,
        smsBody = smsBody,
        pipelineDiagnostic = pipelineDiagnostic,
        timestamp = timestamp,
    )

    @Suppress("LongParameterList")
    suspend fun enqueuePendingBlockedCallLog(
        idempotencyKey: String,
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
        origid: String? = null,
    ) = blocklistRepository.enqueuePendingBlockedCallLog(
        idempotencyKey = idempotencyKey,
        number = number,
        isCall = isCall,
        smsBody = smsBody,
        matchReason = matchReason,
        confidence = confidence,
        timestamp = timestamp,
        ruleId = ruleId,
        pipelineDiagnostic = pipelineDiagnostic,
        origid = origid,
    )

    suspend fun flushPendingBlockedCallLogs(): Int = blocklistRepository.flushPendingBlockedCallLogs()

    suspend fun getPendingBlockedCallLogCount(): Int = blocklistRepository.getPendingBlockedCallLogCount()

    fun getBlockedCalls(): Flow<List<BlockedCall>> = blocklistRepository.getBlockedCalls()

    fun getBlockedCallsByReasonCode(reasonCode: BlockReasonCode): Flow<List<BlockedCall>> = blocklistRepository.getBlockedCallsByReasonCode(reasonCode)

    fun getBlockedCallsOnly(): Flow<List<BlockedCall>> = blocklistRepository.getBlockedCallsOnly()

    fun getBlockedSmsOnly(): Flow<List<BlockedCall>> = blocklistRepository.getBlockedSmsOnly()

    fun getTotalBlockedCount(): Flow<Int> = blocklistRepository.getTotalBlockedCount()

    fun getBlockedCountSince(since: Long): Flow<Int> = blocklistRepository.getBlockedCountSince(since)

    fun getBlockedCountBetween(
        start: Long,
        end: Long,
    ): Flow<Int> = blocklistRepository.getBlockedCountBetween(start, end)

    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = blocklistRepository.getAllSpamNumbers()

    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> = blocklistRepository.getUserBlockedNumbers()

    suspend fun getSpamCount(): Int = blocklistRepository.getSpamCount()

    fun observeSpamCount(): Flow<Int> = blocklistRepository.observeSpamCount()

    suspend fun clearCallLog() = blocklistRepository.clearCallLog()

    suspend fun deleteBlockedCall(call: BlockedCall) = blocklistRepository.deleteBlockedCall(call)

    suspend fun insertBlockedCall(call: BlockedCall) = blocklistRepository.insertBlockedCall(call)

    // ── Search ─────────────────────────────────────────────────────────
    fun searchNumbers(query: String): Flow<List<SpamNumber>> = blocklistRepository.searchNumbers(query)

    // ── Whitelist management ───────────────────────────────────────────
    fun getAllWhitelist(): Flow<List<WhitelistEntry>> = blocklistRepository.getAllWhitelist()

    /** Emergency subset — used by the dedicated Emergency Contacts tab. */
    fun getEmergencyContacts(): Flow<List<WhitelistEntry>> = blocklistRepository.getEmergencyContacts()

    suspend fun addToWhitelist(
        number: String,
        description: String = "",
        isEmergency: Boolean = false,
        expiresAt: Long? = null,
    ) = blocklistRepository.addToWhitelist(number, description, isEmergency, expiresAt)

    suspend fun removeFromWhitelist(entry: WhitelistEntry) = blocklistRepository.removeFromWhitelist(entry)

    /** Toggle emergency flag on an existing whitelist entry without deleting it. */
    suspend fun setWhitelistEmergency(
        id: Long,
        emergency: Boolean,
    ) = blocklistRepository.setWhitelistEmergency(id, emergency)

    // ── Hot list (30-minute trending sync) ────────────────────────────
    suspend fun replaceHotList(numbers: List<SpamNumber>) = syncRepository.replaceHotList(numbers)

    internal suspend fun readHotDataHealth(): HotDataHealth = settingsRepository.readHotDataHealth()

    internal suspend fun recordHotDataHealth(
        lastGoodTimestamp: Long?,
        unavailableFeeds: Set<String>,
    ) = settingsRepository.recordHotDataHealth(lastGoodTimestamp, unavailableFeeds)

    // ── Auto-cleanup ──────────────────────────────────────────────────
    suspend fun cleanupOldLogs() = blocklistRepository.cleanupOldLogs()

    // ── SMS keyword rules ────────────────────────────────────────────
    fun getAllKeywordRules(): Flow<List<SmsKeywordRule>> = blocklistRepository.getAllKeywordRules()

    suspend fun addKeywordRule(
        keyword: String,
        caseSensitive: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) = blocklistRepository.addKeywordRule(keyword, caseSensitive, description, schedule)

    suspend fun deleteKeywordRule(rule: SmsKeywordRule) = blocklistRepository.deleteKeywordRule(rule)

    suspend fun toggleKeywordRule(
        id: Long,
        enabled: Boolean,
    ) = blocklistRepository.toggleKeywordRule(id, enabled)

    fun invalidateRestoredRuleCaches() {
        spamRepositoryImpl.invalidateWildcardCache()
        spamRepositoryImpl.invalidateKeywordCache()
        // Range (hash-wildcard) rules are also restored, and RANGE_RULES is a
        // default restore section — without this the HashWildcardChecker keeps
        // screening with the pre-restore rule set until the process restarts.
        spamRepositoryImpl.invalidateHashWildcardCache()
    }

    /**
     * Run [block] inside a single Room transaction on this repository's own
     * database, so a multi-step mutation (e.g. backup restore's clear +
     * re-insert) commits or rolls back atomically. Uses the repo's actual
     * AppDatabase — the production singleton in the app, or an injected
     * in-memory database under test — so nested DAO calls join the transaction.
     */
    suspend fun <T> runInTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    // ── Search log ───────────────────────────────────────────────────
    fun searchLog(query: String): Flow<List<BlockedCall>> = blocklistRepository.searchLog(query)

    fun normalizeNumber(number: String): String = phoneIdentityCanonicalizer.canonicalizePhone(number)

    fun normalizeSenderIdentity(sender: String): String = phoneIdentityCanonicalizer.canonicalizeIdentity(sender)
}

/**
 * Normalize a raw phone string into the canonical form used everywhere
 * downstream. Strips whitespace, parentheses, punctuation, then keeps
 * **only ASCII '0'..'9'** digits — explicitly NOT `Char.isDigit()` which
 * accepts Arabic-Indic (٠-٩), fullwidth (０-９), and other Unicode digit
 * classes. Allowing those would let a crafted caller-ID bypass exact
 * blocklist matches and prefix rules by sending the same number in a
 * visually-identical but byte-different form. Carrier dialers strip
 * non-ASCII digits before placing a call, so this is the right contract.
 *
 * A leading `+` is preserved; everything else (including embedded `+`,
 * shortcode markers, USSD `#`/`*`) is dropped. Empty result means the
 * caller had no usable digits.
 *
 * Lives as a top-level internal function so JVM unit tests can exercise
 * the canonicalisation without standing up a SpamRepository (and the
 * Context it requires).
 */
internal fun normalizePhoneNumber(number: String): String {
    if (number.length > MAX_RAW_PHONE_NUMBER_LENGTH) return ""
    val digits = StringBuilder(MAX_E164_DIGITS)
    var firstMeaningfulCharacter: Char? = null
    var hasTooManyDigits = false
    for (ch in number) {
        if (ch.code !in PHONE_FORMAT_CONTROL_CODES && !ch.isWhitespace()) {
            firstMeaningfulCharacter = ch
            break
        }
    }
    for (ch in number) {
        if (ch in '0'..'9') {
            if (digits.length >= MAX_E164_DIGITS) {
                hasTooManyDigits = true
                break
            }
            digits.append(ch)
        }
    }
    return when {
        hasTooManyDigits || digits.isEmpty() -> ""
        firstMeaningfulCharacter == '+' -> "+$digits"
        else -> digits.toString()
    }
}

private const val MAX_E164_DIGITS = 15
private const val MAX_RAW_PHONE_NUMBER_LENGTH = 256
private val PHONE_FORMAT_CONTROL_CODES = setOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF)

/**
 * Escape the SQL LIKE wildcard characters so user-typed `%` or `_` is
 * treated literally (prevents a blank/"%" search from returning the
 * whole table). Paired with an ESCAPE '\' clause on the Room queries.
 */
internal fun escapeLikeQuery(query: String): String = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Bridge between the new [BlockResult] type returned by the checker
 * pipeline and the older [SpamCheckResult] shape consumed by the rest
 * of the app (UI, service, notification code). Keeps the checker
 * internals decoupled from legacy call sites.
 */
internal fun BlockResult.toSpamCheckResult(): SpamCheckResult = toSpamCheckResult(null)

internal fun BlockResult.toSpamCheckResult(diagnostics: ScreeningDiagnostics?): SpamCheckResult =
    SpamCheckResult(
        isSpam = shouldBlock,
        matchSource = matchSource,
        type = type,
        description = description,
        confidence = confidence,
        reasonCode = reasonCode,
        ruleId = ruleId,
        screeningDiagnostics = diagnostics,
    )

internal fun sanitizeDatabaseNumbers(
    databaseNumbers: Collection<SpamNumberJson>,
    normalizeNumber: (String) -> String,
    preservedUserBlockedNumbers: Map<String, Long?>,
): List<SpamNumber> =
    databaseNumbers.mapNotNull { json ->
        val normalizedNumber = normalizeNumber(json.number)
        if (normalizedNumber.isBlank()) {
            null
        } else {
            val evidence =
                json.evidence.ifEmpty {
                    listOf(
                        SourceEvidenceJson(
                            sourceId = "github_database",
                            evidenceType = "aggregate_database",
                            license = "CallShield database terms",
                            attribution = "CallShield maintained spam database",
                            firstSeen = json.firstSeen,
                            lastSeen = json.lastSeen,
                            confidenceTier = if (json.reports >= 2) "corroborated" else "unverified",
                            parserVersion = "legacy-v1",
                        ),
                    )
                }
            SpamNumber(
                number = normalizedNumber,
                type = json.type.trim().ifBlank { "unknown" },
                reports = json.reports.coerceAtLeast(1),
                firstSeen = json.firstSeen,
                lastSeen = json.lastSeen,
                description = json.description.trim(),
                source = "github",
                evidenceJson = SourceEvidenceCodec.encode(evidence),
                evidenceExpiresAt = evidence.mapNotNull { it.expiresAtEpochMs }.minOrNull(),
                isUserBlocked = normalizedNumber in preservedUserBlockedNumbers,
                expiresAt = preservedUserBlockedNumbers[normalizedNumber],
            )
        }
    }

internal fun mergeHotListNumbers(
    hotNumbers: Collection<SpamNumber>,
    existingByNumber: Map<String, SpamNumber>,
): List<SpamNumber> =
    hotNumbers.mapNotNull { hotNumber ->
        when (val existing = existingByNumber[hotNumber.number]) {
            null -> {
                hotNumber
            }

            else -> {
                // Never let ephemeral hot-list data overwrite a stronger row from
                // the main database. If we already know this number from GitHub or
                // from a user-owned entry, keep that record and skip the hot insert.
                if (existing.source != "hot_list") {
                    null
                } else {
                    hotNumber.copy(
                        id = existing.id,
                        isUserBlocked = existing.isUserBlocked,
                        expiresAt = existing.expiresAt,
                        evidenceJson = hotNumber.evidenceJson,
                        evidenceExpiresAt = hotNumber.evidenceExpiresAt,
                    )
                }
            }
        }
    }

internal sealed interface SpamNumberWhitelistResolution {
    data object None : SpamNumberWhitelistResolution

    data class Update(
        val number: SpamNumber,
    ) : SpamNumberWhitelistResolution

    data class Delete(
        val number: SpamNumber,
    ) : SpamNumberWhitelistResolution
}

internal fun resolveSpamNumberForWhitelist(existing: SpamNumber?): SpamNumberWhitelistResolution {
    if (existing == null || !existing.isUserBlocked) {
        return SpamNumberWhitelistResolution.None
    }

    return if (existing.source == "user") {
        SpamNumberWhitelistResolution.Delete(existing)
    } else {
        SpamNumberWhitelistResolution.Update(existing.copy(isUserBlocked = false, expiresAt = null))
    }
}
