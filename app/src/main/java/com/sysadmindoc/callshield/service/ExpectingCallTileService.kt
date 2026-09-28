package com.sysadmindoc.callshield.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ExpectingCall
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** Quick Settings tile for "Expecting a call": a tap starts an hour, or ends the window that's running. */
class ExpectingCallTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // The process-wide scope, so closing the shade mid-tap can't cancel the write.
        CallShieldApp.appScope.launch {
            try {
                val repo = SpamRepository.getInstance(applicationContext)
                if (repo.expectingCallUntil.first() > System.currentTimeMillis()) {
                    ExpectingCallController.end(applicationContext, repo)
                } else {
                    ExpectingCallController.start(applicationContext, repo, ExpectingCall.Length.ONE_HOUR)
                }
                withContext(Dispatchers.Main) { updateTile() }
            } catch (e: Exception) {
                android.util.Log.w("ExpectingCallTile", "Tile toggle failed", e)
            }
        }
    }

    private fun updateTile() {
        if (qsTile == null) return
        scope.launch {
            try {
                val until = SpamRepository.getInstance(applicationContext).expectingCallUntil.first()
                val active = until > System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    val tile = qsTile ?: return@withContext
                    tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    tile.label = getString(R.string.expecting_call_title)
                    tile.subtitle =
                        if (active) {
                            getString(R.string.tile_expecting_call_on, DateFormat.getTimeFormat(this@ExpectingCallTileService).format(Date(until)))
                        } else {
                            getString(R.string.tile_expecting_call_off)
                        }
                    tile.updateTile()
                }
            } catch (e: Exception) {
                android.util.Log.w("ExpectingCallTile", "Tile refresh failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
