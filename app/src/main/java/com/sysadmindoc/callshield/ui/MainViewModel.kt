package com.sysadmindoc.callshield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.SyncResult
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    // Settings
    val blockCallsEnabled: StateFlow<Boolean> = repo.blockCallsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val blockSmsEnabled: StateFlow<Boolean> = repo.blockSmsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val blockUnknownEnabled: StateFlow<Boolean> = repo.blockUnknownEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val stirShakenEnabled: StateFlow<Boolean> = repo.stirShakenEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val neighborSpoofEnabled: StateFlow<Boolean> = repo.neighborSpoofEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _spamCount = MutableStateFlow(0)
    val spamCount: StateFlow<Int> = _spamCount

    init {
        viewModelScope.launch {
            _spamCount.value = repo.getSpamCount()
        }
    }

    fun sync() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            val result = repo.syncFromGitHub()
            _syncState.value = if (result.success) {
                _spamCount.value = repo.getSpamCount()
                SyncState.Success(result.message)
            } else {
                SyncState.Error(result.message)
            }
        }
    }

    fun blockNumber(number: String, type: String = "unknown", description: String = "") {
        viewModelScope.launch {
            repo.blockNumber(number, type, description)
        }
    }

    fun unblockNumber(number: SpamNumber) {
        viewModelScope.launch {
            repo.unblockNumber(number)
        }
    }

    fun deleteLogEntry(call: BlockedCall) {
        viewModelScope.launch {
            repo.deleteBlockedCall(call)
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            repo.clearCallLog()
        }
    }

    fun setBlockCalls(enabled: Boolean) = viewModelScope.launch { repo.setBlockCalls(enabled) }
    fun setBlockSms(enabled: Boolean) = viewModelScope.launch { repo.setBlockSms(enabled) }
    fun setBlockUnknown(enabled: Boolean) = viewModelScope.launch { repo.setBlockUnknown(enabled) }
    fun setStirShaken(enabled: Boolean) = viewModelScope.launch { repo.setStirShaken(enabled) }
    fun setNeighborSpoof(enabled: Boolean) = viewModelScope.launch { repo.setNeighborSpoof(enabled) }

    private fun getBlockedCountSince(since: Long): Flow<Int> = repo.getBlockedCalls().map { calls ->
        calls.count { it.wasBlocked && it.timestamp > since }
    }
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
