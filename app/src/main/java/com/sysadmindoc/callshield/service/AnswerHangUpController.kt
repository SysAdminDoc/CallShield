package com.sysadmindoc.callshield.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
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
 *
 * Calls are handled one after another. A second blocked call screened while the first is
 * connected ends the first at once, while endCall() still means that call, and is then
 * answered and hung up itself; see [onIncomingScreeningStarted] and [isHandingOff]. The
 * microphone is muted while a spam call is connected and put back when it ends.
 */
object AnswerHangUpController {
    const val DEFAULT_DELAY_SECONDS = 1
    const val MIN_DELAY_SECONDS = 1
    const val MAX_DELAY_SECONDS = 10

    private const val TAG = "AnswerHangUp"
    private const val ARM_EXPIRY_MS = 5_000L
    private const val ANSWER_TIMEOUT_MS = 5_000L
    private const val POLL_INTERVAL_MS = 250L

    /**
     * How long after ending its own call for a newly screened one the line still counts as
     * this controller's. Telecom gives screening 5 seconds, so the new call's busy check and
     * its arm both land inside it.
     */
    private const val HANDOFF_WINDOW_MS = 5_000L

    private const val MIC_PREFS = "answer_hang_up"
    private const val KEY_MIC_MUTED = "mic_muted"

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

    internal interface Microphone {
        fun isMuted(): Boolean

        fun setMuted(muted: Boolean)

        /**
         * A mute this controller set and hasn't undone yet. Production keeps it on disk so
         * a process killed mid-call still unmutes on the next idle broadcast.
         */
        var heldMute: Boolean
    }

    private data class PendingCall(
        /** Digits of every spelling screening saw; empty for a withheld number. */
        val numbers: Set<String>,
        val armedAt: Long,
        val delayMillis: Long,
        var phase: Phase,
        var acceptedAt: Long? = null,
        var hangUpDeadline: Long? = null,
        /** Armed while the spam call before it was still disconnecting. */
        val afterHandoff: Boolean = false,
        /** A RINGING broadcast arrived while this call was armed. */
        var rang: Boolean = false,
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
    private var microphone: Microphone = productionMicrophone()
    private var pendingCall: PendingCall? = null

    /** When this controller last ended its own call for a newly screened one. */
    private var handoffAt: Long? = null

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
            if (isBusyLocked()) return@synchronized false
            val afterHandoff = isHandingOffLocked()
            clearLocked()
            handoffAt = null
            pendingCall =
                PendingCall(
                    numbers = setOf(digitsOnly(rawNumber), digitsOnly(e164)) - "",
                    armedAt = clock(),
                    delayMillis = clampDelaySeconds(delaySeconds) * 1_000L,
                    phase = Phase.ARMED,
                    afterHandoff = afterHandoff,
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
     * endCall() would reject the new call instead and leave the spam call connected. The
     * new call can then be answered and hung up in turn ([isHandingOff]).
     */
    fun onIncomingScreeningStarted() {
        synchronized(lock) {
            handoffAt = null
            val phase = pendingCall?.phase
            when (phase) {
                Phase.ARMED -> {
                    clearLocked()
                }

                Phase.ANSWERING, Phase.HANGING_UP -> {
                    if (phase == Phase.HANGING_UP && callStateLocked() == TelephonyManager.CALL_STATE_RINGING) {
                        // A call screening never saw, such as a contact waiting, rings
                        // already. endCall() would reject it, so the spam call is left to
                        // the caller with the microphone muted until the line goes idle.
                        clearLocked(keepMicMuted = true)
                    } else {
                        endCallLocked()
                        clearLocked()
                        handoffAt = clock()
                    }
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
            handoffAt = null
            clearLocked()
        }
    }

    fun hasPendingCall(): Boolean = synchronized(lock) { pendingCall != null }

    /** Whether PHONE_STATE broadcasts matter here: a call in flight or a mute to undo. */
    fun wantsPhoneState(): Boolean = synchronized(lock) { pendingCall != null || micHeldLocked() }

    fun isBusy(): Boolean = synchronized(lock) { isBusyLocked() }

    /**
     * True for a few seconds after this controller ended its own spam call for a newly
     * screened one. The phone can still read as on a call while that call disconnects, and
     * that must not keep the new call from being answered and hung up as well. The next
     * screening, an outgoing call or the new call's arm ends it.
     */
    fun isHandingOff(): Boolean = synchronized(lock) { isHandingOffLocked() }

    /** Processes the protected PHONE_STATE broadcast. */
    fun onPhoneState(
        state: String?,
        incomingNumber: String?,
        hasIncomingNumber: Boolean = true,
    ) {
        synchronized(lock) {
            val pending = pendingCall
            if (pending == null) {
                // A spam call left connected for a waiting call kept the mic muted; the
                // mute ends with the last call.
                if (state == TelephonyManager.EXTRA_STATE_IDLE && callStateLocked() != TelephonyManager.CALL_STATE_OFFHOOK) {
                    restoreMicLocked()
                }
                return
            }
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (pending.phase == Phase.ARMED) {
                        pending.rang = true
                        handleRingingLocked(pending, incomingNumber, hasIncomingNumber)
                    }
                }

                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    handleOffhookLocked(pending, incomingNumber)
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // The call this one was armed behind going idle says nothing about
                    // this caller. Once this call has rung, idle means its caller gave up.
                    if (pending.phase == Phase.ARMED && (pending.rang || !pending.afterHandoff)) {
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
        // Telecom unmutes a call as it's answered, so the mute goes on once it's connected.
        muteMicLocked()
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
                        TelephonyManager.CALL_STATE_IDLE -> {
                            clearLocked()
                        }

                        // A ringing call here is one screening never saw, such as a
                        // contact waiting. endCall() would reject it rather than end
                        // the spam call, so the spam call is left to the caller, muted
                        // until the line goes idle.
                        TelephonyManager.CALL_STATE_RINGING -> {
                            clearLocked(keepMicMuted = true)
                        }

                        else -> {
                            if (clock() >= (pending.hangUpDeadline ?: Long.MAX_VALUE)) {
                                endCallLocked()
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

    private fun isBusyLocked(): Boolean = pendingCall?.phase == Phase.ANSWERING || pendingCall?.phase == Phase.HANGING_UP

    private fun isHandingOffLocked(): Boolean = handoffAt?.let { clock() - it < HANDOFF_WINDOW_MS } == true

    private fun isWithinArmWindow(pending: PendingCall): Boolean = clock() - pending.armedAt < ARM_EXPIRY_MS

    private fun endCallLocked() {
        try {
            callControl.endCall()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to hang up screened call", exception)
        }
    }

    /** The phone's call state, or -1 when it can't be read. */
    private fun callStateLocked(): Int =
        try {
            callControl.currentCallState()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to read call state", exception)
            -1
        }

    /** Mutes the microphone unless something else already has, and remembers it did. */
    private fun muteMicLocked() {
        try {
            if (microphone.heldMute || microphone.isMuted()) return
            // Recorded first, so a process that dies between the two still unmutes later.
            microphone.heldMute = true
            microphone.setMuted(true)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to mute the microphone", exception)
        }
    }

    /** Undoes this controller's own mute; a mute someone else set stays. */
    private fun restoreMicLocked() {
        try {
            if (!microphone.heldMute) return
            microphone.setMuted(false)
            microphone.heldMute = false
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to unmute the microphone", exception)
        }
    }

    private fun micHeldLocked(): Boolean =
        try {
            microphone.heldMute
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to read the microphone mute record", exception)
            false
        }

    private fun clearLocked(keepMicMuted: Boolean = false) {
        scheduler.cancelAll()
        pendingCall = null
        if (!keepMicMuted) restoreMicLocked()
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

    private fun productionMicrophone(): Microphone =
        object : Microphone {
            override fun isMuted(): Boolean = audioManager()?.isMicrophoneMute == true

            override fun setMuted(muted: Boolean) {
                audioManager()?.isMicrophoneMute = muted
            }

            override var heldMute: Boolean
                get() = prefs()?.getBoolean(KEY_MIC_MUTED, false) == true
                set(value) {
                    prefs()?.edit()?.putBoolean(KEY_MIC_MUTED, value)?.commit()
                }

            private fun audioManager(): AudioManager? = applicationContext?.getSystemService(AudioManager::class.java)

            private fun prefs() = applicationContext?.getSharedPreferences(MIC_PREFS, Context.MODE_PRIVATE)
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
        microphone: Microphone = productionMicrophone(),
    ) {
        synchronized(lock) {
            // Like a fresh process, these leave a recorded mute for the next idle broadcast.
            clearLocked(keepMicMuted = true)
            handoffAt = null
            this.clock = clock
            this.callControl = callControl
            this.scheduler = scheduler
            this.microphone = microphone
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            clearLocked(keepMicMuted = true)
            handoffAt = null
            applicationContext = null
            clock = SystemClock::elapsedRealtime
            callControl = productionCallControl()
            scheduler = productionScheduler
            microphone = productionMicrophone()
        }
    }

    internal fun phaseForTest(): Phase = synchronized(lock) { pendingCall?.phase ?: Phase.NONE }
}
