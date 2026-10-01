package com.sysadmindoc.callshield.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Warns once per call when a one-time code arrives during a call from someone
 * the user doesn't know, or in the 10 minutes after it. The screening service
 * opens the window; the SMS receiver and the notification listener report
 * every text they read.
 */
internal object CodeDuringCallWarning {
    /** The source for a call the checks never looked at, so the allow list is checked here. */
    const val UNSCREENED = "unscreened"

    private const val TAG = "CodeDuringCallWarning"
    private const val PREFS = "code_during_call"
    private const val KEY_CALLER = "caller"
    private const val KEY_STARTED = "started_at"
    private const val KEY_ENDED = "ended_at"
    private const val KEY_WARNED = "warned"

    /** A call from [number] ("" when hidden) was let ring after the check matched [matchSource]. */
    fun onCallAllowed(
        scope: CoroutineScope,
        context: Context,
        number: String,
        matchSource: String,
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isTrusted: suspend (String) -> Boolean = { allowListed({ SpamRepository.getInstance(context) }, it) },
    ) {
        if (!UnknownCallWindow.countsAsUnknown(matchSource)) return
        val callState = callStateReader(context)
        scope.launch {
            if (matchSource == UNSCREENED && number.isNotEmpty() && isTrusted(number)) return@launch
            val token = window.callStarted(number)
            save(context, window)
            window.watchUntilEnded(token, callState)
            save(context, window)
        }
    }

    /** Returns whether a warning was posted for [body]. */
    fun onMessage(
        context: Context,
        body: String,
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
        notify: (Context, String) -> Boolean = NotificationHelper::notifyCodeDuringCall,
    ): Boolean {
        // The text may have started this process after the call's ended.
        restore(context, window)
        val caller = window.openCaller() ?: return false
        if (!UnknownCallWindow.looksLikeOneTimeCode(body)) return false
        // Checked again here: the user may have saved the caller during the call.
        if (caller.isNotEmpty() && isContact(caller)) return false
        if (!window.claimWarning(caller)) return false
        save(context, window)
        return notify(context, caller)
    }

    /** Whether [number] is on the allow list. When that can't be read, the call is watched. */
    suspend fun allowListed(
        repository: () -> SpamRepository,
        number: String,
    ): Boolean =
        try {
            val repo = repository()
            repo.lookupForms(number).any { repo.hasActiveWhitelistEntry(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't read the allow list", e)
            false
        }

    private fun save(
        context: Context,
        window: UnknownCallWindow,
    ) {
        val saved = window.snapshot() ?: return
        try {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CALLER, saved.caller)
                .putLong(KEY_STARTED, saved.startedAt)
                .putLong(KEY_ENDED, saved.endedAt ?: -1L)
                .putBoolean(KEY_WARNED, saved.warned)
                .apply()
        } catch (e: IllegalStateException) {
            // Before the first unlock there's no app storage; the window stays in memory.
            Log.w(TAG, "Couldn't save the call window", e)
        }
    }

    private fun restore(
        context: Context,
        window: UnknownCallWindow,
    ) {
        if (window.snapshot() != null) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val caller = prefs.getString(KEY_CALLER, null) ?: return
            window.restore(
                UnknownCallWindow.Saved(
                    caller = caller,
                    startedAt = prefs.getLong(KEY_STARTED, 0L),
                    endedAt = prefs.getLong(KEY_ENDED, -1L).takeIf { it >= 0L },
                    warned = prefs.getBoolean(KEY_WARNED, false),
                ),
            )
            // A closed window has nothing more to say; the number needn't stay on disk.
            if (!window.isOpen()) prefs.edit().clear().apply()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Couldn't read the saved call window", e)
        }
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
