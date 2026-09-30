package com.sysadmindoc.callshield.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.sysadmindoc.callshield.data.ExpectingCall
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.flow.first

/** Starts and ends an "Expecting a call" window from Home, the tile or the notification. */
internal object ExpectingCallController {
    suspend fun start(
        context: Context,
        repo: SpamRepository,
        length: ExpectingCall.Length,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val until = ExpectingCall.endsAt(length, now)
        repo.setExpectingCallUntil(until)
        NotificationHelper.showExpectingCall(context, until, now)
        refreshTile(context)
        return until
    }

    suspend fun end(
        context: Context,
        repo: SpamRepository,
    ) {
        repo.setExpectingCallUntil(0L)
        NotificationHelper.cancelExpectingCall(context)
        refreshTile(context)
    }

    /** A reboot clears the ongoing notification while the window may still be running; an ended one is cleared. */
    suspend fun restoreNotification(
        context: Context,
        repo: SpamRepository,
        now: Long = System.currentTimeMillis(),
    ) {
        val until = repo.expectingCallUntil.first()
        if (until > now) NotificationHelper.showExpectingCall(context, until, now) else repo.clearEndedExpectingCall(now)
    }

    private fun refreshTile(context: Context) {
        try {
            TileService.requestListeningState(context, ComponentName(context, ExpectingCallTileService::class.java))
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("ExpectingCall", "Tile refresh refused", e)
        }
    }
}
