package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.EmergencyNumberFloor
import com.sysadmindoc.callshield.data.OutgoingCallGuard
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds an outgoing call to a number CallShield already flags and posts a
 * notification that offers "Call anyway". It runs only while the user has
 * given CallShield the call-redirection role and turned the setting on.
 *
 * Telecom cancels a call it hasn't heard back about within five seconds, so
 * every path answers, and anything that fails or runs long places the call.
 */
class CallShieldRedirectionService : CallRedirectionService() {
    private val answered = AtomicBoolean(false)
    private var pending: Job? = null

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        answered.set(false)
        val appContext = applicationContext
        val rawNumber = handle.schemeSpecificPart.orEmpty()
        val scope = CallShieldApp.appScope
        // Not a child of the coroutine that answers: a contacts query blocks and
        // can't be cancelled, so a slow check is left behind at the deadline
        // rather than holding the answer until it returns.
        val check = scope.async { check(appContext, rawNumber) }
        pending =
            scope.launch {
                val outcome =
                    try {
                        withTimeoutOrNull(DEADLINE_MS) { check.await() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Outgoing call check failed, placing the call", e)
                        null
                    }
                answer(appContext, outcome ?: Outcome(rawNumber, OutgoingCallGuard.Decision.Proceed))
            }
    }

    override fun onRedirectionTimeout() {
        // Telecom has already given up on this call; a late answer would only fail.
        answered.set(true)
        pending?.cancel()
    }

    private data class Outcome(
        val number: String,
        val decision: OutgoingCallGuard.Decision,
    )

    private suspend fun check(
        context: Context,
        rawNumber: String,
    ): Outcome {
        val repository = SpamRepository.getInstance(context)
        val number = repository.normalizeNumber(rawNumber).ifBlank { rawNumber }
        val prefs = repository.readPrefsSnapshot()
        if (prefs[SpamRepository.KEY_OUTGOING_CALL_HOLD] != true || EmergencyNumberFloor.isProtected(rawNumber)) {
            return Outcome(number, OutgoingCallGuard.Decision.Proceed)
        }
        val decision =
            OutgoingCallGuard.decide(
                number = number,
                nowElapsed = SystemClock.elapsedRealtime(),
                trusted = { n -> repository.hasActiveWhitelistEntry(n) || SpamHeuristics.isInContacts(context, n) },
                listed = { n ->
                    repository.findExactSpamNumber(n)?.let { row ->
                        if (row.isUserBlocked) OutgoingCallGuard.Reason.USER_BLOCKLIST else OutgoingCallGuard.Reason.DATABASE
                    }
                },
            )
        return Outcome(number, decision)
    }

    private fun answer(
        context: Context,
        outcome: Outcome,
    ) {
        if (!answered.compareAndSet(false, true)) return
        try {
            when (val decision = outcome.decision) {
                OutgoingCallGuard.Decision.Proceed -> {
                    placeCallUnmodified()
                }

                is OutgoingCallGuard.Decision.Hold -> {
                    cancelCall()
                    NotificationHelper.notifyOutgoingCallHeld(context, outcome.number, decision.reason)
                }
            }
        } catch (e: RuntimeException) {
            // Telecom unbound or timed out first. The call's fate is already decided there.
            Log.w(TAG, "Couldn't answer Telecom about an outgoing call", e)
        }
    }

    private companion object {
        const val TAG = "CallShieldRedirection"

        /** The whole answer, settings read included, stays well inside Telecom's five seconds. */
        const val DEADLINE_MS = 3_000L
    }
}
