package com.sysadmindoc.callshield.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
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
        fun acceptRingingCall()

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
        val number: String,
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

    /** Arms this process for the next matching PHONE_STATE broadcast. */
    fun arm(
        rawNumber: String?,
        delaySeconds: Int,
    ) {
        synchronized(lock) {
            clearLocked()
            pendingCall =
                PendingCall(
                    number = digitsOnly(rawNumber),
                    armedAt = clock(),
                    delayMillis = clampDelaySeconds(delaySeconds) * 1_000L,
                    phase = Phase.ARMED,
                )
            scheduleArmedExpiryLocked()
        }
    }

    fun disarm() {
        synchronized(lock) {
            clearLocked()
        }
    }

    /** A new screening callback invalidates a stale pending, unaccepted call. */
    fun onIncomingScreeningStarted() {
        synchronized(lock) {
            if (pendingCall?.phase == Phase.ARMED) {
                clearLocked()
            }
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
    ) {
        synchronized(lock) {
            val pending = pendingCall ?: return
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> handleRingingLocked(pending, incomingNumber)
                TelephonyManager.EXTRA_STATE_OFFHOOK -> handleOffhookLocked(pending, incomingNumber)
                TelephonyManager.EXTRA_STATE_IDLE -> clearLocked()
            }
        }
    }

    private fun handleRingingLocked(
        pending: PendingCall,
        incomingNumber: String?,
    ) {
        if (pending.phase == Phase.HANGING_UP) {
            // A second incoming call must never be ended by a timer belonging to the first.
            clearLocked()
            return
        }
        if (pending.phase != Phase.ARMED) return
        if (!isWithinArmWindow(pending)) {
            clearLocked()
            return
        }
        if (!numbersMatch(pending.number, digitsOnly(incomingNumber))) return

        pending.phase = Phase.ANSWERING
        pending.acceptedAt = clock()
        try {
            callControl.acceptRingingCall()
        } catch (exception: SecurityException) {
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
            (digitsOnly(incomingNumber).isEmpty() || numbersMatch(pending.number, digitsOnly(incomingNumber)))
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
                } catch (exception: SecurityException) {
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
                        TelephonyManager.CALL_STATE_IDLE,
                        TelephonyManager.CALL_STATE_RINGING,
                        -> {
                            clearLocked()
                        }

                        else -> {
                            if (clock() >= (pending.hangUpDeadline ?: Long.MAX_VALUE)) {
                                try {
                                    callControl.endCall()
                                } catch (exception: SecurityException) {
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
            @Suppress("DEPRECATION")
            override fun acceptRingingCall() {
                telecomManager().acceptRingingCall()
            }

            @Suppress("DEPRECATION")
            override fun endCall(): Boolean = telecomManager().endCall()

            @Suppress("DEPRECATION")
            override fun currentCallState(): Int = applicationContext?.getSystemService(TelephonyManager::class.java)?.callState ?: -1

            private fun telecomManager(): TelecomManager =
                requireNotNull(applicationContext) { "AnswerHangUpController is not configured" }
                    .getSystemService(TelecomManager::class.java)
        }

    private fun digitsOnly(value: String?): String = value.orEmpty().filter(Char::isDigit)

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
