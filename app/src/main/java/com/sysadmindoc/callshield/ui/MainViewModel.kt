package com.sysadmindoc.callshield.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.BlocklistExporter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.service.CallLogScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SpamRepository.getInstance(app)

    val blockedCalls: StateFlow<List<BlockedCall>> = repo.getBlockedCalls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalBlocked: StateFlow<Int> = repo.getTotalBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val blockedToday: StateFlow<Int> = repo.getBlockedCountSince(
        System.currentTimeMillis() - 86_400_000
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val allSpamNumbers: StateFlow<List<SpamNumber>> = repo.getAllSpamNumbers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userBlockedNumbers: StateFlow<List<SpamNumber>> = repo.getUserBlockedNumbers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wildcardRules: StateFlow<List<WildcardRule>> = repo.getAllWildcardRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistEntries: StateFlow<List<WhitelistEntry>> = repo.getAllWhitelist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Onboarding
    val onboardingDone: StateFlow<Boolean> = repo.onboardingDone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true) // default true to avoid flash

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    val searchResults: StateFlow<List<SpamNumber>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.length >= 2) repo.searchNumbers(query) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Detail navigation
    private val _selectedNumber = MutableStateFlow<String?>(null)
    val selectedNumber: StateFlow<String?> = _selectedNumber

    // Settings
    val blockCallsEnabled = repo.blockCallsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val blockSmsEnabled = repo.blockSmsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val blockUnknownEnabled = repo.blockUnknownEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val stirShakenEnabled = repo.stirShakenEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val neighborSpoofEnabled = repo.neighborSpoofEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val heuristicsEnabled = repo.heuristicsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val smsContentEnabled = repo.smsContentEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val contactWhitelistEnabled = repo.contactWhitelistEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val aggressiveModeEnabled = repo.aggressiveModeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val timeBlockEnabled = repo.timeBlockEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val timeBlockStart = repo.timeBlockStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 22)
    val timeBlockEnd = repo.timeBlockEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)
    val freqEscalationEnabled = repo.freqEscalationEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _spamCount = MutableStateFlow(0)
    val spamCount: StateFlow<Int> = _spamCount

    private val _scanResult = MutableStateFlow<CallLogScanner.ScanResult?>(null)
    val scanResult: StateFlow<CallLogScanner.ScanResult?> = _scanResult

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult

    private val _restoreResult = MutableStateFlow<String?>(null)
    val restoreResult: StateFlow<String?> = _restoreResult

    init {
        viewModelScope.launch {
            _spamCount.value = repo.getSpamCount()
            // Auto-sync on first launch
            if (_spamCount.value == 0) sync()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repo.setOnboardingDone()
            // Trigger first sync after onboarding
            sync()
        }
    }

    fun sync() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            val result = repo.syncFromGitHub()
            _syncState.value = if (result.success) {
                _spamCount.value = repo.getSpamCount()
                SyncState.Success(result.message)
            } else SyncState.Error(result.message)
        }
    }

    fun scanCallLog() { viewModelScope.launch { _scanResult.value = CallLogScanner.scan(getApplication()) } }

    // Search
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    // Detail navigation
    fun openNumberDetail(number: String) { _selectedNumber.value = number }
    fun closeNumberDetail() { _selectedNumber.value = null }

    // Blocklist
    fun blockNumber(number: String, type: String = "unknown", description: String = "") {
        viewModelScope.launch { repo.blockNumber(number, type, description) }
    }
    fun unblockNumber(number: SpamNumber) { viewModelScope.launch { repo.unblockNumber(number) } }
    fun deleteLogEntry(call: BlockedCall) { viewModelScope.launch { repo.deleteBlockedCall(call) } }
    fun clearLog() { viewModelScope.launch { repo.clearCallLog() } }

    // Wildcards
    fun addWildcardRule(pattern: String, isRegex: Boolean, description: String) {
        viewModelScope.launch { repo.addWildcardRule(pattern, isRegex, description) }
    }
    fun deleteWildcardRule(rule: WildcardRule) { viewModelScope.launch { repo.deleteWildcardRule(rule) } }
    fun toggleWildcardRule(id: Long, enabled: Boolean) { viewModelScope.launch { repo.toggleWildcardRule(id, enabled) } }

    // Whitelist
    fun addToWhitelist(number: String, description: String = "") {
        viewModelScope.launch { repo.addToWhitelist(number, description) }
    }
    fun removeFromWhitelist(entry: WhitelistEntry) {
        viewModelScope.launch { repo.removeFromWhitelist(entry) }
    }

    // Export/import
    fun exportBlocklist() {
        val numbers = userBlockedNumbers.value
        if (numbers.isNotEmpty()) BlocklistExporter.exportAndShare(getApplication(), numbers)
    }
    fun importBlocklist(uri: Uri) {
        viewModelScope.launch {
            val count = BlocklistExporter.importFromUri(getApplication(), uri)
            _importResult.value = "Imported $count numbers"
        }
    }

    // Backup/restore
    fun backup() { viewModelScope.launch { BackupRestore.shareBackup(getApplication()) } }
    fun restore(uri: Uri) {
        viewModelScope.launch {
            val result = BackupRestore.restoreFromUri(getApplication(), uri)
            _restoreResult.value = result.message
        }
    }

    // Settings
    fun setBlockCalls(v: Boolean) = viewModelScope.launch { repo.setBlockCalls(v) }
    fun setBlockSms(v: Boolean) = viewModelScope.launch { repo.setBlockSms(v) }
    fun setBlockUnknown(v: Boolean) = viewModelScope.launch { repo.setBlockUnknown(v) }
    fun setStirShaken(v: Boolean) = viewModelScope.launch { repo.setStirShaken(v) }
    fun setNeighborSpoof(v: Boolean) = viewModelScope.launch { repo.setNeighborSpoof(v) }
    fun setHeuristics(v: Boolean) = viewModelScope.launch { repo.setHeuristics(v) }
    fun setSmsContent(v: Boolean) = viewModelScope.launch { repo.setSmsContent(v) }
    fun setContactWhitelist(v: Boolean) = viewModelScope.launch { repo.setContactWhitelist(v) }
    fun setAggressiveMode(v: Boolean) = viewModelScope.launch { repo.setAggressiveMode(v) }
    fun setTimeBlock(v: Boolean) = viewModelScope.launch { repo.setTimeBlock(v) }
    fun setTimeBlockStart(h: Int) = viewModelScope.launch { repo.setTimeBlockStart(h) }
    fun setTimeBlockEnd(h: Int) = viewModelScope.launch { repo.setTimeBlockEnd(h) }
    fun setFreqEscalation(v: Boolean) = viewModelScope.launch { repo.setFreqEscalation(v) }
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
