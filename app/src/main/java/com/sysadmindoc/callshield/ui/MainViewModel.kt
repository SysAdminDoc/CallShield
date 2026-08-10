@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.sysadmindoc.callshield.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.data.BlocklistExporter
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.ContactGroup
import com.sysadmindoc.callshield.data.ContactGroupCatalog
import com.sysadmindoc.callshield.data.EmergencyNumberFloor
import com.sysadmindoc.callshield.data.MessageCapabilitySource
import com.sysadmindoc.callshield.data.MessageCapabilityStatus
import com.sysadmindoc.callshield.data.RuleConflictAnalyzer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.StandingRuleConflict
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.checker.PipelineTrace
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.ExternalBlocklistPreview
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.service.CallLogScanner
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.SmsInboxScanner
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import com.sysadmindoc.callshield.ui.theme.syncApplicationNightMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A user-facing status line plus whether it represents success. Carrying the
 * boolean means the UI never has to sniff the (localized) message text with
 * `startsWith("Restored ")` / `startsWith("Applied")` to pick success vs error
 * styling — that broke under non-English locales and the shipped pseudolocales.
 */
data class StatusMessage(
    val text: String,
    val success: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions")
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val appContext: Context,
        private val repo: SpamRepository,
        private val syncDatabase: SyncDatabaseUseCase,
        private val manageBlocklist: ManageBlocklistUseCase,
        private val exportLogs: ExportLogsUseCase,
    ) : ViewModel() {
        val blockedCalls: StateFlow<List<BlockedCall>> =
            repo
                .getBlockedCalls()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val totalBlocked: StateFlow<Int> =
            repo
                .getTotalBlockedCount()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        // Rolling time anchor — re-emits the current wall-clock every minute so the
        // "today / this week / last week" counts below stay accurate when the app is
        // left open for long periods instead of baking a frozen timestamp into the
        // Room query at VM construction time.
        private val timeAnchor: Flow<Long> =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(60_000)
                }
            }

        val blockedToday: StateFlow<Int> =
            timeAnchor
                .flatMapLatest { now ->
                    val windows = buildDashboardTimeWindows(now)
                    repo.getBlockedCountSince(windows.todayStart)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        val blockedThisWeek: StateFlow<Int> =
            timeAnchor
                .flatMapLatest { now ->
                    val windows = buildDashboardTimeWindows(now)
                    repo.getBlockedCountSince(windows.weekStart)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        val blockedLastWeek: StateFlow<Int> =
            timeAnchor
                .flatMapLatest { now ->
                    val windows = buildDashboardTimeWindows(now)
                    repo.getBlockedCountBetween(windows.lastWeekStart, windows.lastWeekEnd)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        val allSpamNumbers: StateFlow<List<SpamNumber>> =
            repo
                .getAllSpamNumbers()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val userBlockedNumbers: StateFlow<List<SpamNumber>> =
            repo
                .getUserBlockedNumbers()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val wildcardRules: StateFlow<List<WildcardRule>> =
            repo
                .getAllWildcardRules()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val hashWildcardRules: StateFlow<List<HashWildcardRule>> =
            repo
                .getAllHashWildcardRules()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val whitelistEntries: StateFlow<List<WhitelistEntry>> =
            repo
                .getAllWhitelist()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val liveRuleConflicts: Flow<List<StandingRuleConflict>> =
            combine(
                userBlockedNumbers,
                wildcardRules,
                hashWildcardRules,
                whitelistEntries,
                repo.observeAllPrefixes(),
            ) { exactBlocks, wildcards, hashWildcards, whitelist, prefixes ->
                RuleConflictAnalyzer.audit(
                    exactBlocks = exactBlocks,
                    wildcardRules = wildcards,
                    hashWildcardRules = hashWildcards,
                    whitelist = whitelist,
                    prefixes = prefixes,
                )
            }

        val ruleConflicts: StateFlow<List<StandingRuleConflict>> =
            liveRuleConflicts
                .combine(repo.dismissedRuleConflictKeys) { conflicts, dismissedKeys ->
                    conflicts.filterNot { it.key in dismissedKeys }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val emergencyContacts: StateFlow<List<WhitelistEntry>> =
            repo
                .getEmergencyContacts()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val keywordRules: StateFlow<List<SmsKeywordRule>> =
            repo
                .getAllKeywordRules()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Onboarding — tri-state: null until DataStore first emits, so the shell
        // renders a neutral surface for one frame instead of flashing the main
        // UI before onboarding (new install) or onboarding before the main UI.
        val onboardingDone: StateFlow<Boolean?> =
            repo.onboardingDone
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        // Search
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery
        val searchResults: StateFlow<List<SpamNumber>> =
            _searchQuery
                .debounce(300)
                .flatMapLatest { query ->
                    if (query.length >= 2) repo.searchNumbers(query) else flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Detail navigation
        private val _selectedNumber = MutableStateFlow<String?>(null)
        val selectedNumber: StateFlow<String?> = _selectedNumber

        /**
         * Result of the Lookup tab's last check, held here so it survives tab
         * switches and rotation.
         *
         * [LookupOutcome.number] is the number that was actually checked. The
         * screen must render and act on that rather than on the live text
         * field, otherwise editing the input after a check leaves the previous
         * verdict on screen attached to a number that was never checked — and
         * Block/Report would act on it.
         */
        data class LookupOutcome(
            val number: String,
            val result: SpamCheckResult,
            val trace: PipelineTrace?,
        )

        private val _lookupOutcome = MutableStateFlow<LookupOutcome?>(null)
        val lookupOutcome: StateFlow<LookupOutcome?> = _lookupOutcome

        fun clearLookupOutcome() {
            _lookupOutcome.value = null
        }

        fun recordLookupOutcome(
            number: String,
            result: SpamCheckResult,
            trace: PipelineTrace?,
        ) {
            _lookupOutcome.value = LookupOutcome(number, result, trace)
        }

        // Settings
        val appTheme =
            repo.appTheme
                .map(AppThemeMode::fromStorage)
                // Seed from the synchronous SharedPreferences mirror so cold starts
                // paint the user's real theme on the first frame instead of flashing
                // the Amoled default until DataStore emits.
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    AppThemeMode.fromStorage(repo.cachedAppTheme()),
                )
        val blockCallsEnabled = repo.blockCallsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val blockSmsEnabled = repo.blockSmsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val blockUnknownEnabled = repo.blockUnknownEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val stirShakenEnabled = repo.stirShakenEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val stirTrustedAllowEnabled = repo.stirTrustedAllowEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val autoMuteLowConfidenceEnabled = repo.autoMuteLowConfidenceEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val neighborSpoofEnabled = repo.neighborSpoofEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val heuristicsEnabled = repo.heuristicsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val smsContentEnabled = repo.smsContentEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val smsBurstEnabled = repo.smsBurstEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val urlhausStripQueryEnabled =
            repo.urlhausStripQueryEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val urlhausRemoteLookupEnabled =
            repo.urlhausRemoteLookupEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val liveCallerEnrichmentEnabled =
            repo.liveCallerEnrichmentEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val contactWhitelistEnabled = repo.contactWhitelistEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val contactsOnlyEnabled =
            repo.contactsOnlyEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val selectedContactGroups =
            repo.selectedContactGroups
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        val outgoingRiskWarningEnabled =
            repo.outgoingRiskWarningEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        private val _contactGroups = MutableStateFlow<List<ContactGroup>>(emptyList())
        val contactGroups: StateFlow<List<ContactGroup>> = _contactGroups
        private val _contactGroupsLoading = MutableStateFlow(false)
        val contactGroupsLoading: StateFlow<Boolean> = _contactGroupsLoading
        val regionBlockEnabled =
            repo.regionBlockEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val allowedRegions =
            repo.allowedRegions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        val cnapTrustPatterns =
            repo.cnapTrustPatterns
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        val cnapBlockPatterns =
            repo.cnapBlockPatterns
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        val categoryCallActions =
            repo.categoryCallActions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
        val dbPrefixExpansionEnabled =
            repo.dbPrefixExpansionEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val aggressiveModeEnabled = repo.aggressiveModeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val answeredCallerTrustEnabled =
            repo.answeredCallerTrustEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val answeredCallerThreshold =
            repo.answeredCallerThreshold.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD,
            )
        val answeredCallerWindowDays =
            repo.answeredCallerWindowDays.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
            )
        val emergencyCallbackGraceEnabled =
            repo.emergencyCallbackGraceEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val emergencyCallbackWindowMinutes =
            repo.emergencyCallbackWindowMinutes.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
            )
        val timeBlockEnabled = repo.timeBlockEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val timeBlockStart = repo.timeBlockStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 22)
        val timeBlockEnd = repo.timeBlockEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)
        val freqEscalationEnabled = repo.freqEscalationEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val freqThreshold = repo.freqThreshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)
        val autoCleanupEnabled = repo.autoCleanupEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val cleanupDays = repo.cleanupDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

        val lastSyncTimestamp = repo.lastSyncTimestamp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
        val lastSyncSource = repo.lastSyncSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val mlScorerEnabled = repo.mlScorerEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val rcsFilterEnabled = repo.rcsFilterEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        internal val smsMessageCapabilityStatus =
            repo.smsMessageCapabilityStatus.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                MessageCapabilityStatus.notObserved(MessageCapabilitySource.SMS_BROADCAST, Build.VERSION.SDK_INT),
            )
        internal val notificationMessageCapabilityStatus =
            repo.notificationMessageCapabilityStatus.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                MessageCapabilityStatus.notObserved(MessageCapabilitySource.NOTIFICATION_LISTENER, Build.VERSION.SDK_INT),
            )
        val postCallScreenEnabled =
            repo.postCallScreenEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val notificationScreeningPackages =
            repo.notificationScreeningPackages.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.sysadmindoc.callshield.data.NotificationScreeningSources.defaultEnabledPackages,
            )
        val silentVoicemailEnabled = repo.silentVoicemailEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val pushAlertEnabled = repo.pushAlertEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        val pushAlertDisabledPackages =
            repo.pushAlertDisabledPackages
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        val externalBlocklistSubscriptions =
            repo.externalBlocklistSubscriptions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState

        // Observe the row count directly from Room so it stays correct after any
        // mutation (sync, import, restore, manual block/unblock) without manual
        // refresh calls that were easy to forget on new write paths.
        val spamCount: StateFlow<Int> =
            repo
                .observeSpamCount()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        private val _scanResult = MutableStateFlow<CallLogScanner.ScanResult?>(null)
        val scanResult: StateFlow<CallLogScanner.ScanResult?> = _scanResult

        private val _smsScanResult = MutableStateFlow<SmsInboxScanner.ScanResult?>(null)
        val smsScanResult: StateFlow<SmsInboxScanner.ScanResult?> = _smsScanResult

        private val _scanningCalls = MutableStateFlow(false)
        val scanningCalls: StateFlow<Boolean> = _scanningCalls

        private val _scanningSms = MutableStateFlow(false)
        val scanningSms: StateFlow<Boolean> = _scanningSms

        private val _importResult = MutableStateFlow<String?>(null)
        val importResult: StateFlow<String?> = _importResult

        private val _restoreResult = MutableStateFlow<StatusMessage?>(null)
        val restoreResult: StateFlow<StatusMessage?> = _restoreResult

        private val _restorePreview = MutableStateFlow<BackupRestore.RestorePreview?>(null)
        val restorePreview: StateFlow<BackupRestore.RestorePreview?> = _restorePreview

        private val _externalBlocklistPreview = MutableStateFlow<ExternalBlocklistPreview?>(null)
        val externalBlocklistPreview: StateFlow<ExternalBlocklistPreview?> = _externalBlocklistPreview

        private val _externalBlocklistResult = MutableStateFlow<StatusMessage?>(null)
        val externalBlocklistResult: StateFlow<StatusMessage?> = _externalBlocklistResult

        fun clearImportResult() {
            _importResult.value = null
        }

        fun clearRestoreResult() {
            _restoreResult.value = null
        }

        fun clearRestorePreview() {
            _restorePreview.value = null
        }

        fun clearExternalBlocklistResult() {
            _externalBlocklistPreview.value = null
            _externalBlocklistResult.value = null
        }

        fun clearContributeResult() {
            _contributeResult.value = null
        }

        init {
            viewModelScope.launch {
                val initialCount = repo.getSpamCount()
                val onboardingAlreadyDone = repo.onboardingDone.first()
                if (initialCount == 0 && onboardingAlreadyDone) sync(showProgress = false)
            }
            // Backfill the synchronous theme mirror so the next cold start reflects
            // the current theme even for users who set it before this cache existed.
            viewModelScope.launch {
                val persistedTheme = AppThemeMode.fromStorage(repo.appTheme.first())
                repo.cacheAppTheme(persistedTheme.storageValue)
                repo.cachePostCallScreenEnabled(repo.postCallScreenEnabled.first())
                syncApplicationNightMode(appContext, persistedTheme)
            }
            if (appContext.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                refreshContactGroups()
            }
        }

        fun completeOnboarding() {
            viewModelScope.launch {
                repo.setOnboardingDone()
                // Trigger first sync after onboarding
                sync()
            }
        }

        fun sync() = sync(showProgress = true)

        private fun sync(showProgress: Boolean) {
            viewModelScope.launch {
                _syncState.value = SyncState.Syncing
                if (showProgress) NotificationHelper.showSyncProgress(appContext)
                try {
                    val result = syncDatabase(force = true)
                    _syncState.value =
                        if (result.success) {
                            if (result.warning) {
                                SyncState.Warning(result.message)
                            } else {
                                SyncState.Success(result.message)
                            }
                        } else {
                            SyncState.Error(result.message)
                        }
                } finally {
                    if (showProgress) NotificationHelper.hideSyncProgress(appContext)
                }
            }
        }

        fun previewExternalBlocklist(
            url: String,
            label: String = "",
        ) {
            viewModelScope.launch {
                val result = repo.previewExternalBlocklistSubscription(url, label)
                _externalBlocklistPreview.value = result.preview
                _externalBlocklistResult.value = StatusMessage(result.message, result.success)
            }
        }

        fun applyExternalBlocklist(
            url: String,
            label: String = "",
        ) {
            viewModelScope.launch {
                val result = repo.applyExternalBlocklistSubscription(url, label)
                _externalBlocklistPreview.value = if (result.success) null else result.preview
                _externalBlocklistResult.value = StatusMessage(result.message, result.success)
            }
        }

        fun setExternalBlocklistEnabled(
            subscription: ExternalBlocklistSubscription,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                val result = repo.setExternalBlocklistSubscriptionEnabled(subscription.id, enabled)
                _externalBlocklistResult.value = StatusMessage(result.message, result.success)
            }
        }

        fun removeExternalBlocklist(subscription: ExternalBlocklistSubscription) {
            viewModelScope.launch {
                val result = repo.removeExternalBlocklistSubscription(subscription.id)
                _externalBlocklistResult.value = StatusMessage(result.message, result.success)
            }
        }

        fun scanCallLog() {
            if (_scanningCalls.value) return
            _scanningCalls.value = true
            viewModelScope.launch {
                try {
                    _scanResult.value = CallLogScanner.scan(appContext)
                } catch (e: Exception) {
                    _scanResult.value =
                        CallLogScanner.ScanResult(
                            0,
                            0,
                            emptyList(),
                            error = scanFailedMessage(e),
                        )
                } finally {
                    _scanningCalls.value = false
                }
            }
        }

        fun scanSmsInbox() {
            if (_scanningSms.value) return
            _scanningSms.value = true
            viewModelScope.launch {
                try {
                    _smsScanResult.value = SmsInboxScanner.scan(appContext)
                } catch (e: Exception) {
                    _smsScanResult.value =
                        SmsInboxScanner.ScanResult(
                            0,
                            0,
                            emptyList(),
                            error = scanFailedMessage(e),
                        )
                } finally {
                    _scanningSms.value = false
                }
            }
        }

        // Search
        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        // Detail navigation
        fun openNumberDetail(number: String) {
            // Canonicalize before matching — the detail screen compares against
            // canonicalized store rows, so a national-format call-log number
            // ("555-123-4567") must be normalized or it finds nothing.
            _selectedNumber.value = repo.normalizeNumber(number)
        }

        fun closeNumberDetail() {
            _selectedNumber.value = null
        }

        // Blocklist
        fun blockNumber(
            number: String,
            type: String = "unknown",
            description: String = "",
        ) {
            if (EmergencyNumberFloor.isProtected(number)) {
                Toast
                    .makeText(
                        appContext,
                        appContext.getString(R.string.emergency_number_block_refused),
                        Toast.LENGTH_LONG,
                    ).show()
                return
            }
            viewModelScope.launch {
                manageBlocklist.blockNumber(number, type, description)
            }
        }

        fun temporaryBlockNumber(
            number: String,
            durationMillis: Long,
            type: String = "spam",
            description: String = "",
        ) {
            val expiresAt = System.currentTimeMillis() + durationMillis.coerceAtLeast(0L)
            viewModelScope.launch { manageBlocklist.temporaryBlockNumber(number, expiresAt, type, description) }
        }

        fun temporaryAllowNumber(
            number: String,
            durationMillis: Long,
            description: String = "",
        ) {
            val expiresAt = System.currentTimeMillis() + durationMillis.coerceAtLeast(0L)
            viewModelScope.launch { manageBlocklist.temporaryAllowNumber(number, expiresAt, description) }
        }

        fun unblockNumber(number: SpamNumber) {
            viewModelScope.launch { manageBlocklist.unblockNumber(number) }
        }

        fun dismissRuleConflict(key: String) {
            viewModelScope.launch { repo.dismissRuleConflict(key) }
        }

        /**
         * Restore a removed block by re-inserting the exact captured row, so an
         * undo preserves a temporary block's expiry (and reports/firstSeen)
         * instead of re-deriving it as a permanent block.
         */
        fun restoreBlockedNumber(entity: SpamNumber) {
            viewModelScope.launch { repo.restoreBlockedNumber(entity) }
        }

        /** Undo a block created by number string (e.g. an accidental log swipe). */
        fun unblockByNumber(number: String) {
            viewModelScope.launch { repo.unblockByNumber(number) }
        }

        fun deleteLogEntry(call: BlockedCall) {
            viewModelScope.launch { repo.deleteBlockedCall(call) }
        }

        fun restoreLogEntry(call: BlockedCall) {
            viewModelScope.launch { repo.insertBlockedCall(call) }
        }

        fun clearLog() {
            viewModelScope.launch { repo.clearCallLog() }
        }

        // Wildcards
        fun addWildcardRule(
            pattern: String,
            isRegex: Boolean,
            description: String,
            schedule: TimeSchedule = TimeSchedule(),
        ) {
            viewModelScope.launch { manageBlocklist.addWildcardRule(pattern, isRegex, description, schedule) }
        }

        fun deleteWildcardRule(rule: WildcardRule) {
            viewModelScope.launch { manageBlocklist.deleteWildcardRule(rule) }
        }

        // Hash wildcard rules (A5 — length-locked `#` patterns)
        //
        // addHashWildcardRule returns a Boolean via the repository but the
        // ViewModel wrapper is fire-and-forget — the Compose layer validates
        // patterns before calling this so any rejected write is already a bug.
        fun addHashWildcardRule(
            pattern: String,
            description: String = "",
            schedule: TimeSchedule = TimeSchedule(),
        ) {
            viewModelScope.launch { manageBlocklist.addHashWildcardRule(pattern, description, schedule) }
        }

        fun deleteHashWildcardRule(rule: HashWildcardRule) {
            viewModelScope.launch { manageBlocklist.deleteHashWildcardRule(rule) }
        }

        fun toggleHashWildcardRule(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch { manageBlocklist.toggleHashWildcardRule(id, enabled) }
        }

        // SMS keyword rules
        fun addKeywordRule(
            keyword: String,
            caseSensitive: Boolean = false,
            description: String = "",
            schedule: TimeSchedule = TimeSchedule(),
        ) {
            viewModelScope.launch { manageBlocklist.addKeywordRule(keyword, caseSensitive, description, schedule) }
        }

        fun deleteKeywordRule(rule: SmsKeywordRule) {
            viewModelScope.launch { manageBlocklist.deleteKeywordRule(rule) }
        }

        fun toggleKeywordRule(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch { manageBlocklist.toggleKeywordRule(id, enabled) }
        }

        fun toggleWildcardRule(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch { manageBlocklist.toggleWildcardRule(id, enabled) }
        }

        // Whitelist
        fun addToWhitelist(
            number: String,
            description: String = "",
            isEmergency: Boolean = false,
        ) {
            viewModelScope.launch { manageBlocklist.addToWhitelist(number, description, isEmergency) }
        }

        fun removeFromWhitelist(entry: WhitelistEntry) {
            viewModelScope.launch { manageBlocklist.removeFromWhitelist(entry) }
        }

        fun toggleWhitelistEmergency(
            id: Long,
            emergency: Boolean,
        ) {
            viewModelScope.launch { manageBlocklist.setWhitelistEmergency(id, emergency) }
        }

        // ── Undo support for rule deletion ────────────────────────────────
        // Deleting a rule used to be instant, silent, and unrecoverable, with
        // the X sitting right next to each rule's enable switch. Re-adding
        // from the deleted row restores every field except the row id, which
        // is autogenerated and carries no meaning.
        fun restoreWildcardRule(rule: WildcardRule) {
            addWildcardRule(rule.pattern, rule.isRegex, rule.description, rule.schedule)
        }

        fun restoreHashWildcardRule(rule: HashWildcardRule) {
            addHashWildcardRule(rule.pattern, rule.description, rule.schedule)
        }

        fun restoreKeywordRule(rule: SmsKeywordRule) {
            addKeywordRule(rule.keyword, rule.caseSensitive, rule.description, rule.schedule)
        }

        fun restoreWhitelistEntry(entry: WhitelistEntry) {
            addToWhitelist(entry.number, entry.description, entry.isEmergency)
        }

        // Export/import
        fun exportBlocklist() {
            val numbers = userBlockedNumbers.value
            if (numbers.isEmpty()) return
            launchExport {
                exportLogs.exportBlocklist(numbers)
            }
        }

        /**
         * Exports/backups write to cache storage and can legitimately fail
         * (full data partition, oversized encrypted payload). An uncaught
         * throw in [viewModelScope] crashes the process — catch, log, and
         * tell the user instead.
         */
        private fun launchExport(work: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    work()
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Export failed", e)
                    Toast
                        .makeText(appContext, appContext.getString(R.string.export_share_failed), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }

        fun importBlocklist(uri: Uri) {
            viewModelScope.launch {
                val result = BlocklistExporter.importFromUri(appContext, uri)
                _importResult.value = result.message
            }
        }

        // Backup/restore
        fun backup(
            sections: Set<BackupRestore.BackupSection> = BackupRestore.defaultExportSections,
            passphrase: CharArray? = null,
        ) {
            val ownedPassphrase = passphrase?.copyOf()
            passphrase?.fill('\u0000')
            launchExport {
                try {
                    BackupRestore.shareBackup(appContext, sections, ownedPassphrase)
                } finally {
                    ownedPassphrase?.fill('\u0000')
                }
            }
        }

        fun restore(
            uri: Uri,
            sections: Set<BackupRestore.BackupSection> = BackupRestore.defaultRestoreSections,
            passphrase: CharArray? = null,
        ) {
            val ownedPassphrase = passphrase?.copyOf()
            passphrase?.fill('\u0000')
            viewModelScope.launch {
                try {
                    val result = BackupRestore.previewRestoreFromUri(appContext, uri, sections, ownedPassphrase)
                    if (result.success) {
                        _restorePreview.value = result.preview
                        _restoreResult.value = null
                    } else {
                        _restorePreview.value = null
                        _restoreResult.value = StatusMessage(result.message, success = false)
                    }
                } finally {
                    ownedPassphrase?.fill('\u0000')
                }
            }
        }

        fun applyRestore(mode: BackupRestore.RestoreMode) {
            val preview =
                _restorePreview.value ?: run {
                    _restoreResult.value =
                        StatusMessage(appContext.getString(R.string.backup_restore_no_preview), success = false)
                    return
                }
            viewModelScope.launch {
                val result = BackupRestore.restoreFromPreview(appContext, preview, mode)
                _restoreResult.value = StatusMessage(result.message, result.success)
                if (result.success) {
                    _restorePreview.value = null
                }
            }
        }

        // Settings
        fun setAppTheme(theme: AppThemeMode) =
            viewModelScope.launch {
                repo.setAppTheme(theme.storageValue)
                syncApplicationNightMode(appContext, theme)
            }

        fun setBlockCalls(v: Boolean) = viewModelScope.launch { repo.setBlockCalls(v) }

        fun setBlockSms(v: Boolean) = viewModelScope.launch { repo.setBlockSms(v) }

        fun setBlockUnknown(v: Boolean) = viewModelScope.launch { repo.setBlockUnknown(v) }

        fun setStirShaken(v: Boolean) = viewModelScope.launch { repo.setStirShaken(v) }

        fun setStirTrustedAllow(v: Boolean) = viewModelScope.launch { repo.setStirTrustedAllow(v) }

        fun setAutoMuteLowConfidence(v: Boolean) = viewModelScope.launch { repo.setAutoMuteLowConfidence(v) }

        fun setNeighborSpoof(v: Boolean) = viewModelScope.launch { repo.setNeighborSpoof(v) }

        fun setHeuristics(v: Boolean) = viewModelScope.launch { repo.setHeuristics(v) }

        fun setSmsContent(v: Boolean) = viewModelScope.launch { repo.setSmsContent(v) }

        fun setSmsBurst(v: Boolean) = viewModelScope.launch { repo.setSmsBurst(v) }

        fun setUrlhausStripQuery(v: Boolean) = viewModelScope.launch { repo.setUrlhausStripQuery(v) }

        fun setUrlhausRemoteLookup(v: Boolean) = viewModelScope.launch { repo.setUrlhausRemoteLookup(v) }

        fun setLiveCallerEnrichment(v: Boolean) = viewModelScope.launch { repo.setLiveCallerEnrichment(v) }

        fun setContactWhitelist(v: Boolean) = viewModelScope.launch { repo.setContactWhitelist(v) }

        fun setContactsOnly(v: Boolean) = viewModelScope.launch { repo.setContactsOnly(v) }

        fun setSelectedContactGroups(groupKeys: Set<String>) =
            viewModelScope.launch {
                repo.setSelectedContactGroups(groupKeys)
                SpamHeuristics.clearContactCache()
            }

        fun setOutgoingRiskWarning(enabled: Boolean) =
            viewModelScope.launch {
                repo.setOutgoingRiskWarning(enabled)
            }

        fun refreshContactGroups() {
            viewModelScope.launch {
                (appContext.applicationContext as? CallShieldApp)?.ensureContactsObserver()
                SpamHeuristics.clearContactCache()
                _contactGroupsLoading.value = true
                try {
                    val groups =
                        withContext(Dispatchers.IO) {
                            ContactGroupCatalog.loadGroups(appContext)
                        }
                    val selectedKeys = repo.selectedContactGroups.first()
                    val migratedKeys = ContactGroupCatalog.migrateSelectedKeys(groups, selectedKeys)
                    if (migratedKeys != selectedKeys) {
                        repo.setSelectedContactGroups(migratedKeys)
                        SpamHeuristics.clearContactCache()
                    }
                    _contactGroups.value = groups
                } finally {
                    _contactGroupsLoading.value = false
                }
            }
        }

        fun saveRegionAndCnapRules(
            regionBlockEnabled: Boolean,
            allowedRegions: Set<String>,
            cnapTrustPatterns: Set<String>,
            cnapBlockPatterns: Set<String>,
        ) = viewModelScope.launch {
            repo.setAllowedRegions(allowedRegions)
            repo.setRegionBlock(regionBlockEnabled && allowedRegions.isNotEmpty())
            repo.setCnapTrustPatterns(cnapTrustPatterns)
            repo.setCnapBlockPatterns(cnapBlockPatterns)
        }

        fun setCategoryCallAction(
            category: CallCategory,
            action: CategoryCallAction,
        ) = viewModelScope.launch { repo.setCategoryCallAction(category, action) }

        fun setDbPrefixExpansion(v: Boolean) = viewModelScope.launch { repo.setDbPrefixExpansion(v) }

        fun setAggressiveMode(v: Boolean) = viewModelScope.launch { repo.setAggressiveMode(v) }

        fun setAnsweredCallerTrust(v: Boolean) = viewModelScope.launch { repo.setAnsweredCallerTrust(v) }

        fun setAnsweredCallerThreshold(v: Int) = viewModelScope.launch { repo.setAnsweredCallerThreshold(v) }

        fun setFreqThreshold(v: Int) = viewModelScope.launch { repo.setFreqThreshold(v) }

        fun setAnsweredCallerWindowDays(v: Int) = viewModelScope.launch { repo.setAnsweredCallerWindowDays(v) }

        fun setEmergencyCallbackGrace(v: Boolean) = viewModelScope.launch { repo.setEmergencyCallbackGrace(v) }

        fun setEmergencyCallbackWindowMinutes(v: Int) = viewModelScope.launch { repo.setEmergencyCallbackWindowMinutes(v) }

        fun setTimeBlock(v: Boolean) = viewModelScope.launch { repo.setTimeBlock(v) }

        fun setTimeBlockStart(h: Int) = viewModelScope.launch { repo.setTimeBlockStart(h) }

        fun setTimeBlockEnd(h: Int) = viewModelScope.launch { repo.setTimeBlockEnd(h) }

        fun setFreqEscalation(v: Boolean) = viewModelScope.launch { repo.setFreqEscalation(v) }

        fun setAutoCleanup(v: Boolean) = viewModelScope.launch { repo.setAutoCleanup(v) }

        fun setCleanupDays(d: Int) = viewModelScope.launch { repo.setCleanupDays(d) }

        fun setMlScorer(v: Boolean) = viewModelScope.launch { repo.setMlScorer(v) }

        fun setRcsFilter(v: Boolean) = viewModelScope.launch { repo.setRcsFilter(v) }

        fun setPostCallScreen(v: Boolean) = viewModelScope.launch { repo.setPostCallScreen(v) }

        fun setNotificationScreeningPackage(
            packageName: String,
            enabled: Boolean,
        ) = viewModelScope.launch { repo.setNotificationScreeningPackage(packageName, enabled) }

        fun resetNotificationScreeningPackages() = viewModelScope.launch { repo.resetNotificationScreeningPackages() }

        fun setSilentVoicemail(v: Boolean) = viewModelScope.launch { repo.setSilentVoicemail(v) }

        fun setPushAlert(v: Boolean) = viewModelScope.launch { repo.setPushAlert(v) }

        /** Per-package opt-in/out for the A3 allowlist editor. */
        fun setPushAlertPackageAllowed(
            pkg: String,
            allowed: Boolean,
        ) {
            viewModelScope.launch { repo.togglePushAlertPackage(pkg, allowed) }
        }

        fun resetPushAlertPackages() {
            viewModelScope.launch { repo.resetPushAlertPackages() }
        }

        // Profiles
        // Persisted in DataStore (SpamRepository.KEY_ACTIVE_PROFILE) so the dashboard
        // chip stays selected across process death and ViewModel recreation. Issue #2:
        // tapping "Maximum" reset on next render because the StateFlow was in-memory only.
        val activeProfile: StateFlow<BlockingProfiles.Profile?> =
            repo.activeProfileName
                .map { name -> name?.let { runCatching { BlockingProfiles.Profile.valueOf(it) }.getOrNull() } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        fun applyProfile(profile: BlockingProfiles.Profile) {
            viewModelScope.launch {
                try {
                    BlockingProfiles.apply(appContext, profile)
                    repo.setActiveProfileName(profile.name)
                } catch (_: Exception) {
                    repo.setActiveProfileName(null)
                }
            }
        }

        // Anonymous community contribution
        private val _contributeResult = MutableStateFlow<StatusMessage?>(null)
        val contributeResult: StateFlow<StatusMessage?> = _contributeResult

        fun contributeToDatabase(
            number: String,
            type: String = "spam",
        ) {
            viewModelScope.launch {
                val result = CommunityContributor.contribute(repo.normalizeNumber(number), type)
                _contributeResult.value = result.toStatusMessage()
            }
        }

        fun reportNotSpam(number: String) {
            viewModelScope.launch {
                // Whitelist locally AND report as false positive to community
                manageBlocklist.addToWhitelist(number, appContext.getString(R.string.desc_reported_not_spam))
                val result = CommunityContributor.reportNotSpam(repo.normalizeNumber(number))
                _contributeResult.value = result.toStatusMessage()
            }
        }

        /**
         * Localize the typed outcome. The network layer's `message` field is
         * hardcoded-English diagnostics — showing it verbatim regressed the
         * v1.7.26 StatusMessage i18n contract on the Number Detail screen.
         */
        private fun CommunityContributor.ContributeResult.toStatusMessage(): StatusMessage {
            val text =
                when (outcome) {
                    CommunityContributor.ContributeOutcome.REPORTED_SPAM -> {
                        appContext.getString(R.string.contribute_reported_spam)
                    }

                    CommunityContributor.ContributeOutcome.REPORTED_NOT_SPAM -> {
                        appContext.getString(R.string.contribute_reported_not_spam)
                    }

                    CommunityContributor.ContributeOutcome.INVALID_NUMBER -> {
                        appContext.getString(R.string.contribute_invalid_number)
                    }

                    CommunityContributor.ContributeOutcome.RATE_LIMITED -> {
                        appContext.resources.getQuantityString(
                            R.plurals.contribute_rate_limited,
                            retryAfterSeconds,
                            retryAfterSeconds,
                        )
                    }

                    CommunityContributor.ContributeOutcome.SERVER_ERROR -> {
                        appContext.getString(R.string.contribute_server_error)
                    }

                    CommunityContributor.ContributeOutcome.NETWORK_ERROR -> {
                        appContext.getString(R.string.contribute_network_error)
                    }
                }
            return StatusMessage(text, success)
        }

        // Share spam warning
        fun shareAsSpam(
            number: String,
            reason: String = "",
        ) {
            com.sysadmindoc.callshield.data.SpamSharer
                .share(appContext, number, reason)
        }

        // Log export
        fun exportLog(includeRawSmsBodies: Boolean = false) {
            launchExport {
                // Read the log directly rather than sampling the blockedCalls
                // StateFlow. That flow is WhileSubscribed, and Settings never
                // collects it — launching straight into Settings (e.g. via the
                // Lookup shortcut) left it at its initial empty value, so Export
                // silently did nothing with a full log on disk.
                val calls = repo.getBlockedCalls().first()
                if (calls.isEmpty()) {
                    Toast
                        .makeText(appContext, appContext.getString(R.string.export_log_empty), Toast.LENGTH_SHORT)
                        .show()
                    return@launchExport
                }
                exportLogs.exportBlockedLog(calls, includeRawSmsBodies)
            }
        }

        fun exportRedressLog() {
            launchExport {
                val calls = repo.getBlockedCalls().first().filter { it.isCall && it.wasBlocked }
                if (calls.isEmpty()) {
                    Toast
                        .makeText(appContext, appContext.getString(R.string.export_redress_empty), Toast.LENGTH_SHORT)
                        .show()
                    return@launchExport
                }
                exportLogs.exportRedressLog(calls)
            }
        }

        private fun scanFailedMessage(error: Throwable): String =
            appContext.getString(
                R.string.dashboard_scan_failed,
                error.message ?: appContext.getString(R.string.dashboard_scan_unknown_error),
            )
    }

sealed class SyncState {
    data object Idle : SyncState()

    data object Syncing : SyncState()

    data class Success(
        val message: String,
    ) : SyncState()

    data class Warning(
        val message: String,
    ) : SyncState()

    data class Error(
        val message: String,
    ) : SyncState()
}
