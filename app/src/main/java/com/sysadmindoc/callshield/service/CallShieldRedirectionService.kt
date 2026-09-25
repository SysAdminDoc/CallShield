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
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds an outgoing call to a number CallShield already flags and posts a
 * notification that offers "Call anyway". It runs only while the user has
 * given CallShield the call-redirection role and turned the setting on.
 *
 * Telecom cancels a call it hasn't heard back about within five seconds, so
 * every path answers. Anything that fails or runs long places the call,
 * except a premium-rate or callback-scam number, which is held on its rule
 * alone, and a hold happens only when its notification can reach the user.
 */
class CallShieldRedirectionService : CallRedirectionService() {
    // Tests supply an isolated repository and scope; production uses the app's.
    internal var repository: SpamRepository? = null
    internal var scope: CoroutineScope? = null

    // Telecom keeps one reply channel per service, and a second call placed
    // before the first is answered takes it over. Only the call Telecom is
    // waiting on may answer, so a slow check from an earlier call can't
    // hold or place the next one. Set and read on the main thread.
    private var current: PendingCall? = null

    // Calls Telecom may still time out, oldest first. Its timeout names no
    // call, but every call waits the same time, so a timeout belongs to the
    // oldest call that hasn't answered: a call whose reply channel a later
    // call took over never answers, and times out while the later call
    // checks. Main thread only.
    private val waiting = ArrayDeque<PendingCall>()

    private class PendingCall(
        val placedAt: Long,
    ) {
        val answered = AtomicBoolean(false)

        @Volatile var job: Job? = null

        // A premium-rate or callback-scam number is known to need a hold before
        // any lookup runs. If the lookups outlast the deadline, this is the answer
        // instead of letting the call through.
        @Volatile var provisional: Outcome? = null
    }

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        val call = PendingCall(SystemClock.elapsedRealtime())
        forgetStale(call.placedAt)
        waiting.addLast(call)
        current = call
        val appContext = applicationContext
        val rawNumber = handle.schemeSpecificPart.orEmpty()
        val scope = this.scope ?: CallShieldApp.appScope
        // Not a child of the coroutine that answers: a contacts query blocks and
        // can't be cancelled, so a slow check is left behind at the deadline
        // rather than holding the answer until it returns.
        val check = scope.async { check(appContext, rawNumber, allowInteractiveResponse, call) }
        call.job =
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
                // On the main thread, where Telecom hands over each call's reply
                // channel, so no new call can take it between the check and the answer.
                withContext(Dispatchers.Main) {
                    answer(appContext, call, outcome ?: call.provisional ?: Outcome(rawNumber, OutgoingCallGuard.Decision.Proceed))
                }
            }
    }

    override fun onRedirectionTimeout() {
        // Telecom has already given up on this call; a late answer would only fail.
        forgetStale(SystemClock.elapsedRealtime())
        waiting.removeFirstOrNull()?.let { call ->
            call.answered.set(true)
            call.job?.cancel()
        }
    }

    /** Drops calls older than any Telecom timeout: they answered or ended some other way. */
    private fun forgetStale(now: Long) {
        while (waiting.firstOrNull()?.let { now - it.placedAt > STALE_AFTER_MS } == true) {
            waiting.removeFirst()
        }
    }

    private data class Outcome(
        val number: String,
        val decision: OutgoingCallGuard.Decision,
    )

    private suspend fun check(
        context: Context,
        rawNumber: String,
        allowInteractiveResponse: Boolean,
        call: PendingCall,
    ): Outcome {
        val repository = this.repository ?: SpamRepository.getInstance(context)
        val number = repository.normalizeNumber(rawNumber).ifBlank { rawNumber }
        val prefs = repository.readPrefsSnapshot()
        val proceed = Outcome(number, OutgoingCallGuard.Decision.Proceed)
        if (prefs[SpamRepository.KEY_OUTGOING_CALL_HOLD] != true ||
            EmergencyNumberFloor.isProtected(rawNumber) ||
            EmergencyNumberFloor.isProtected(number)
        ) {
            return proceed
        }
        // A hold is a cancelled call plus a notification. With no way to show
        // the notification (car mode, which Telecom signals by refusing an
        // interactive response, or notifications turned off) it would be a
        // call that silently fails, so the call goes through.
        if (!allowInteractiveResponse || !NotificationHelper.canShowOutgoingHold(context)) {
            return proceed
        }
        val now = SystemClock.elapsedRealtime()
        val bypassKey = OutgoingCallGuard.bypassKey(number, PhoneIdentityCanonicalizer.cachedFromContext(context).homeRegionIso)
        if (!OutgoingCallGuard.isBypassed(bypassKey, now)) {
            OutgoingCallGuard.ruleReason(number)?.let { call.provisional = Outcome(number, OutgoingCallGuard.Decision.Hold(it)) }
        }
        val decision =
            OutgoingCallGuard.decide(
                number = number,
                nowElapsed = now,
                bypassKey = bypassKey,
                trusted = { n ->
                    repository.lookupForms(n).any { repository.hasActiveWhitelistEntry(it) } || SpamHeuristics.isInContacts(context, n)
                },
                listed = { n ->
                    repository.lookupForms(n).firstNotNullOfOrNull { repository.findExactSpamNumber(it) }?.let { row ->
                        if (row.isUserBlocked) OutgoingCallGuard.Reason.USER_BLOCKLIST else OutgoingCallGuard.Reason.DATABASE
                    }
                },
            )
        return Outcome(number, decision)
    }

    private fun answer(
        context: Context,
        call: PendingCall,
        outcome: Outcome,
    ) {
        // A later call owns the reply channel now; this answer would land on it.
        if (current !== call || !call.answered.compareAndSet(false, true)) return
        waiting.remove(call)
        try {
            when (val decision = outcome.decision) {
                OutgoingCallGuard.Decision.Proceed -> {
                    placeCallUnmodified()
                }

                is OutgoingCallGuard.Decision.Hold -> {
                    // Post first: if the notification can't go up after all, the
                    // call goes through rather than vanishing without a word.
                    if (NotificationHelper.notifyOutgoingCallHeld(context, outcome.number, decision.reason)) {
                        cancelCall()
                    } else {
                        placeCallUnmodified()
                    }
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

        /** Well past Telecom's redirection timeout (five seconds by default). */
        const val STALE_AFTER_MS = 30_000L
    }
}
