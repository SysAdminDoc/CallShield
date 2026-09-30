package com.sysadmindoc.callshield.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Warns once per call when a one-time code arrives during a call from someone
 * the user doesn't know, or in the 10 minutes after it. The screening service
 * opens the window; the SMS receiver and the notification listener report
 * every text they read.
 */
internal object CodeDuringCallWarning {
    /** A call from [number] ("" when hidden) was let ring after the check matched [matchSource]. */
    fun onCallAllowed(
        scope: CoroutineScope,
        context: Context,
        number: String,
        matchSource: String,
        window: UnknownCallWindow = UnknownCallWindow.shared,
    ) {
        if (!UnknownCallWindow.countsAsUnknown(matchSource)) return
        val token = window.callStarted(number)
        val callState = callStateReader(context)
        scope.launch { window.watchUntilEnded(token, callState) }
    }

    /** Returns whether a warning was posted for [body]. */
    fun onMessage(
        context: Context,
        body: String,
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
        notify: (Context, String) -> Boolean = NotificationHelper::notifyCodeDuringCall,
    ): Boolean {
        val caller = window.openCaller() ?: return false
        if (!UnknownCallWindow.looksLikeOneTimeCode(body)) return false
        // Checked again here: the user may have saved the caller during the call.
        if (caller.isNotEmpty() && isContact(caller)) return false
        if (!window.claimWarning(caller)) return false
        return notify(context, caller)
    }

    private fun callStateReader(context: Context): () -> Int? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return { null }
        val telephony =
            try {
                context.getSystemService(TelephonyManager::class.java)
            } catch (_: RuntimeException) {
                null
            } ?: return { null }
        return {
            try {
                @Suppress("DEPRECATION")
                telephony.callState
            } catch (_: SecurityException) {
                null
            }
        }
    }
}
