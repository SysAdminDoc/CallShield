package com.sysadmindoc.callshield.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Coordinates the short answer-and-hang-up sequence for one screened call.
 *
 * Telecom and phone-state callbacks are not guaranteed to arrive in a fixed order, so this
 * process-wide controller owns both the pending identity and all delayed work.
 */
object AnswerHangUpController {
    const val DEFAULT_DELAY_SECONDS = 1
    const val MIN_DELAY_SECONDS = 1
    const val MAX_DELAY_SECONDS = 10

    private const val TAG = "AnswerHangUp"
    private const val ARM_EXPIRY_MS = 5_000L
    private const val ANSWER_TIMEOUT_MS = 5_000L
    private const val POLL_INTERVAL_MS = 250L

    internal enum class Phase {
        NONE,
        ARMED,
        ANSWERING,
        HANGING_UP,
    }

    internal interface CallControl {
        fun acceptRingingCallAudioOnly(): Boolean

        fun endCall(): Boolean

        fun currentCallState(): Int
    }

    internal interface Scheduler {
        fun postDelayed(
            delayMillis: Long,
            task: () -> Unit,
        )

        fun cancelAll()
    }

    private data class PendingCall(
        /** Digits of every spelling screening saw; empty for a withheld number. */
        val numbers: Set<String>,
        val armedAt: Long,
        val delayMillis: Long,
        var phase: Phase,
        var acceptedAt: Long? = null,
        var hangUpDeadline: Long? = null,
    )

    private val lock = Any()
    private val timerToken = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val productionScheduler =
        object : Scheduler {
            override fun postDelayed(
                delayMillis: Long,
                task: () -> Unit,
            ) {
                mainHandler.postAtTime(
                    Runnable { task() },
                    timerToken,
                    SystemClock.uptimeMillis() + delayMillis,
                )
            }

            override fun cancelAll() {
                mainHandler.removeCallbacksAndMessages(timerToken)
            }
        }

    private var applicationContext: Context? = null
    private var clock: () -> Long = SystemClock::elapsedRealtime
    private var scheduler: Scheduler = productionScheduler
    private var callControl: CallControl = productionCallControl()
    private var pendingCall: PendingCall? = null

    /** Stores the context used by the production telecom implementation. */
    internal fun configure(context: Context) {
        applicationContext = context.applicationContext
    }

    fun clampDelaySeconds(value: Int?): Int = value?.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS) ?: DEFAULT_DELAY_SECONDS

    /**
     * Arms this process for the next matching PHONE_STATE broadcast when no call is in flight.
     *
     * [rawNumber] is the caller ID as Telecom delivered it and [e164] the form screening
     * normalized it to. PHONE_STATE carries the network's spelling, which can be national
     * (a UK 07911 number is +447911 in E.164), so the ringing call may match either.
     */
    fun tryArm(
        rawNumber: String?,
        delaySeconds: Int,
        e164: String? = null,
    ): Boolean =
        synchronized(lock) {
            if (pendingCall?.phase == Phase.ANSWERING || pendingCall?.phase == Phase.HANGING_UP) {
                return@synchronized false
            }
            clearLocked()
            pendingCall =
                PendingCall(
                    numbers = setOf(digitsOnly(rawNumber), digitsOnly(e164)) - "",
                    armedAt = clock(),
                    delayMillis = clampDelaySeconds(delaySeconds) * 1_000L,
                    phase = Phase.ARMED,
                )
            scheduleArmedExpiryLocked()
            true
        }

    fun disarm() {
        synchronized(lock) {
            clearLocked()
        }
    }

    /**
     * A new screening callback invalidates a stale pending, unaccepted call. A call arriving
     * while the spam call is answered and waiting out its delay ends that call now: the new
     * call isn't ringing yet, so endCall() still ends the spam call, and once it rings
     * endCall() would reject the new call instead and leave the spam call connected.
     */
    fun onIncomingScreeningStarted() {
        synchronized(lock) {
            when (pendingCall?.phase) {
                Phase.ARMED -> {
                    clearLocked()
                }

                Phase.ANSWERING, Phase.HANGING_UP -> {
                    try {
                        callControl.endCall()
                    } catch (exception: RuntimeException) {
                        Log.w(TAG, "Unable to hang up screened call", exception)
                    }
                    clearLocked()
                }

                Phase.NONE, null -> {
                    Unit
                }
            }
        }
    }

    /** A user-initiated outgoing call must never inherit a pending hang-up timer. */
    fun onOutgoingCallStarted() {
        synchronized(lock) {
            clearLocked()
        }
    }

    fun hasPendingCall(): Boolean = synchronized(lock) { pendingCall != null }

    fun isBusy(): Boolean =
        synchronized(lock) {
            pendingCall?.phase == Phase.ANSWERING || pendingCall?.phase == Phase.HANGING_UP
        }

    /** Processes the protected PHONE_STATE broadcast. */
    fun onPhoneState(
        state: String?,
        incomingNumber: String?,
        hasIncomingNumber: Boolean = true,
    ) {
        synchronized(lock) {
            val pending = pendingCall ?: return
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (pending.phase == Phase.ARMED) {
                        handleRingingLocked(pending, incomingNumber, hasIncomingNumber)
                    }
                }

                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    handleOffhookLocked(pending, incomingNumber)
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (pending.phase == Phase.ARMED) {
                        clearLocked()
                    }
                }
            }
        }
    }

    private fun handleRingingLocked(
        pending: PendingCall,
        incomingNumber: String?,
        hasIncomingNumber: Boolean,
    ) {
        if (!hasIncomingNumber) return
        if (!isWithinArmWindow(pending)) {
            clearLocked()
            return
        }
        if (!matchesPending(pending, digitsOnly(incomingNumber))) return

        pending.phase = Phase.ANSWERING
        pending.acceptedAt = clock()
        try {
            if (!callControl.acceptRingingCallAudioOnly()) {
                clearLocked()
                return
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to answer screened call", exception)
            clearLocked()
            return
        }
        schedulePollLocked()
    }

    private fun handleOffhookLocked(
        pending: PendingCall,
        incomingNumber: String?,
    ) {
        if (pending.phase == Phase.ANSWERING &&
            (digitsOnly(incomingNumber).isEmpty() || matchesPending(pending, digitsOnly(incomingNumber)))
        ) {
            enterHangingUpLocked(pending)
        }
    }

    private fun enterHangingUpLocked(pending: PendingCall) {
        if (pending.phase != Phase.ANSWERING) return
        pending.phase = Phase.HANGING_UP
        pending.hangUpDeadline = clock() + pending.delayMillis
        schedulePollLocked()
    }

    private fun scheduleArmedExpiryLocked() {
        scheduler.cancelAll()
        scheduler.postDelayed(ARM_EXPIRY_MS) {
            synchronized(lock) {
                val pending = pendingCall
                if (pending?.phase == Phase.ARMED && !isWithinArmWindow(pending)) {
                    clearLocked()
                }
            }
        }
    }

    private fun schedulePollLocked() {
        scheduler.cancelAll()
        scheduler.postDelayed(POLL_INTERVAL_MS) { pollCallState() }
    }

    private fun pollCallState() {
        synchronized(lock) {
            val pending = pendingCall ?: return
            val callState =
                try {
                    callControl.currentCallState()
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Unable to read screened call state", exception)
                    clearLocked()
                    return
                }
            when (pending.phase) {
                Phase.ANSWERING -> {
                    when (callState) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            enterHangingUpLocked(pending)
                        }

                        TelephonyManager.CALL_STATE_IDLE -> {
                            clearLocked()
                        }

                        else -> {
                            val acceptedAt = pending.acceptedAt ?: pending.armedAt
                            if (clock() - acceptedAt >= ANSWER_TIMEOUT_MS) {
                                clearLocked()
                            }
                        }
                    }
                }

                Phase.HANGING_UP -> {
                    when (callState) {
                        // A ringing call here is one screening never saw, such as a
                        // contact waiting. endCall() would reject it rather than end
                        // the spam call, so the spam call is left to the caller.
                        TelephonyManager.CALL_STATE_IDLE,
                        TelephonyManager.CALL_STATE_RINGING,
                        -> {
                            clearLocked()
                        }

                        else -> {
                            if (clock() >= (pending.hangUpDeadline ?: Long.MAX_VALUE)) {
                                try {
                                    callControl.endCall()
                                } catch (exception: RuntimeException) {
                                    Log.w(TAG, "Unable to hang up screened call", exception)
                                }
                                clearLocked()
                            }
                        }
                    }
                }

                Phase.NONE,
                Phase.ARMED,
                -> {
                    Unit
                }
            }
            if (pendingCall?.phase == Phase.ANSWERING || pendingCall?.phase == Phase.HANGING_UP) {
                schedulePollLocked()
            }
        }
    }

    private fun isWithinArmWindow(pending: PendingCall): Boolean = clock() - pending.armedAt < ARM_EXPIRY_MS

    private fun clearLocked() {
        scheduler.cancelAll()
        pendingCall = null
    }

    private fun productionCallControl(): CallControl =
        object : CallControl {
            // The preceding permission gate is real, but lint does not trace this helper.
            @SuppressLint("MissingPermission")
            @Suppress("DEPRECATION")
            override fun acceptRingingCallAudioOnly(): Boolean {
                if (!hasAnswerPhoneCallsPermission()) return false
                telecomManager().acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)
                return true
            }

            // The preceding permission gate is real, but lint does not trace this helper.
            @SuppressLint("MissingPermission")
            @Suppress("DEPRECATION")
            override fun endCall(): Boolean {
                if (!hasAnswerPhoneCallsPermission()) return false
                return telecomManager().endCall()
            }

            @Suppress("DEPRECATION")
            override fun currentCallState(): Int = applicationContext?.getSystemService(TelephonyManager::class.java)?.callState ?: -1

            private fun hasAnswerPhoneCallsPermission(): Boolean =
                applicationContext?.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

            private fun telecomManager(): TelecomManager =
                requireNotNull(applicationContext) { "AnswerHangUpController is not configured" }
                    .getSystemService(TelecomManager::class.java)
        }

    private fun digitsOnly(value: String?): String = value.orEmpty().filter(Char::isDigit)

    private fun matchesPending(
        pending: PendingCall,
        incoming: String,
    ): Boolean = if (pending.numbers.isEmpty()) incoming.isEmpty() else pending.numbers.any { numbersMatch(it, incoming) }

    private fun numbersMatch(
        first: String,
        second: String,
    ): Boolean {
        if (first.isEmpty() && second.isEmpty()) return true
        if (first.isEmpty() || second.isEmpty()) return false
        if (first == second) return true
        val shorter = if (first.length <= second.length) first else second
        val longer = if (first.length <= second.length) second else first
        return shorter.length >= 7 && longer.endsWith(shorter)
    }

    internal fun setTestDependencies(
        clock: () -> Long,
        callControl: CallControl,
        scheduler: Scheduler,
    ) {
        synchronized(lock) {
            clearLocked()
            this.clock = clock
            this.callControl = callControl
            this.scheduler = scheduler
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            clearLocked()
            applicationContext = null
            clock = SystemClock::elapsedRealtime
            callControl = productionCallControl()
            scheduler = productionScheduler
        }
    }

    internal fun phaseForTest(): Phase = synchronized(lock) { pendingCall?.phase ?: Phase.NONE }
}
