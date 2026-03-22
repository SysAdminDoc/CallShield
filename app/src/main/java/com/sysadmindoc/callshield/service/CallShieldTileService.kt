package com.sysadmindoc.callshield.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile — toggle call/SMS blocking from the notification shade.
 */
class CallShieldTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = SpamRepository.getInstance(applicationContext)
        runBlocking {
            val callsEnabled = repo.blockCallsEnabled.first()
            val smsEnabled = repo.blockSmsEnabled.first()
            val newState = !(callsEnabled || smsEnabled)
            repo.setBlockCalls(newState)
            repo.setBlockSms(newState)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val repo = SpamRepository.getInstance(applicationContext)
        val active = runBlocking {
            repo.blockCallsEnabled.first() || repo.blockSmsEnabled.first()
        }
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "CallShield"
        tile.subtitle = if (active) "Protection on" else "Protection off"
        tile.updateTile()
    }
}
