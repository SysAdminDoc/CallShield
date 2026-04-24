package com.sysadmindoc.callshield.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quick Settings tile — toggle call/SMS blocking from the notification shade.
 *
 * ## Concurrency (v1.6.3)
 *
 * Two rapid taps previously raced: each launch read the current state,
 * computed the opposite, and wrote — so a tap+tap within the DataStore
 * write latency flipped the toggle *to the same value twice*, leaving
 * it apparently stuck. A single [Mutex] now serializes the read-modify-
 * write so back-to-back taps alternate correctly.
 */
class CallShieldTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val toggleMutex = Mutex()

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            toggleMutex.withLock {
                val repo = SpamRepository.getInstance(applicationContext)
                val callsEnabled = repo.blockCallsEnabled.first()
                val smsEnabled = repo.blockSmsEnabled.first()
                val newState = !(callsEnabled || smsEnabled)
                repo.setBlockCalls(newState)
                repo.setBlockSms(newState)
            }
            withContext(Dispatchers.Main) { updateTile() }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        scope.launch {
            val repo = SpamRepository.getInstance(applicationContext)
            val active = repo.blockCallsEnabled.first() || repo.blockSmsEnabled.first()
            withContext(Dispatchers.Main) {
                val currentTile = qsTile ?: return@withContext
                currentTile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                currentTile.label = "CallShield"
                currentTile.subtitle = if (active) "Protection on" else "Protection off"
                currentTile.updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
