package com.sysadmindoc.callshield.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
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
        // Run the read-modify-write on the process-wide scope, not the service
        // scope: if the shade is dismissed and the TileService is destroyed
        // mid-toggle, onDestroy cancels `scope` and could persist only one of the
        // two flags, leaving call/SMS blocking split.
        CallShieldApp.appScope.launch {
            // appScope has no CoroutineExceptionHandler, and DataStore
            // surfaces an unreadable prefs file as an IOException from the
            // flow — without the guard a tile tap on a corrupted file would
            // kill the whole process (and there is no DataStore self-heal
            // like the Room corruption recovery).
            try {
                toggleMutex.withLock {
                    val repo = SpamRepository.getInstance(applicationContext)
                    val callsEnabled = repo.blockCallsEnabled.first()
                    val smsEnabled = repo.blockSmsEnabled.first()
                    val newState = !(callsEnabled || smsEnabled)
                    repo.setBlockCalls(newState)
                    repo.setBlockSms(newState)
                }
                withContext(Dispatchers.Main) { updateTile() }
            } catch (e: Exception) {
                android.util.Log.w("CallShieldTile", "Tile toggle failed", e)
            }
        }
    }

    private fun updateTile() {
        if (qsTile == null) return
        scope.launch {
            try {
                val repo = SpamRepository.getInstance(applicationContext)
                val active = repo.blockCallsEnabled.first() || repo.blockSmsEnabled.first()
                withContext(Dispatchers.Main) {
                    val currentTile = qsTile ?: return@withContext
                    currentTile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    currentTile.label = getString(R.string.app_name)
                    currentTile.subtitle =
                        getString(
                            if (active) R.string.tile_protection_on else R.string.tile_protection_off,
                        )
                    currentTile.updateTile()
                }
            } catch (e: Exception) {
                android.util.Log.w("CallShieldTile", "Tile refresh failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
