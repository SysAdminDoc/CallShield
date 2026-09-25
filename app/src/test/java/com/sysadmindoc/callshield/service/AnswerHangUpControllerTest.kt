package com.sysadmindoc.callshield.service

import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerHangUpControllerTest {
    private lateinit var clock: FakeClock
    private lateinit var callControl: FakeCallControl
    private lateinit var scheduler: FakeScheduler

    @Before
    fun setUp() {
        clock = FakeClock()
        callControl = FakeCallControl()
        scheduler = FakeScheduler(clock)
        AnswerHangUpController.setTestDependencies(clock::now, callControl, scheduler)
    }

    @After
    fun tearDown() {
        AnswerHangUpController.resetForTest()
    }

    @Test
    fun `matching ringing accepts audio only once and duplicate ringing is ignored`() {
        arm("5551234567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(1, callControl.audioOnlyAcceptCalls)
        assertEquals(AnswerHangUpController.Phase.ANSWERING, AnswerHangUpController.phaseForTest())
    }

    @Test
    fun `different ringing number does not answer`() {
        arm("5551234567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5557654321")

        assertEquals(0, callControl.audioOnlyAcceptCalls)
        assertTrue(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `ringing after arm expiry does not answer`() {
        arm("5551234567", 1)
        clock.advanceBy(5_000)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(0, callControl.audioOnlyAcceptCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `offhook ends exactly once at the delay deadline`() {
        arm("5551234567", 2)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK

        repeat(7) { scheduler.advanceBy(250) }
        assertEquals(0, callControl.endCalls)

        scheduler.advanceBy(250)
        assertEquals(1, callControl.endCalls)
        scheduler.advanceBy(1_000)
        assertEquals(1, callControl.endCalls)
    }

    @Test
    fun `idle before deadline never ends the call`() {
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        callControl.currentState = TelephonyManager.CALL_STATE_IDLE

        scheduler.advanceBy(2_000)

        assertEquals(0, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `stale ringing and idle broadcasts during hangup do not prevent the deadline hangup`() {
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5557654321")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        repeat(4) { scheduler.advanceBy(250) }

        assertEquals(1, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `polled offhook without broadcast still hangs up`() {
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK

        scheduler.advanceBy(250)
        repeat(4) { scheduler.advanceBy(250) }

        assertEquals(1, callControl.endCalls)
    }

    @Test
    fun `withheld number accepts null ringing number`() {
        arm(null, 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, null)

        assertEquals(1, callControl.audioOnlyAcceptCalls)
    }

    @Test
    fun `north american prefix variants match on digits`() {
        arm("+1 (555) 123-4567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(1, callControl.audioOnlyAcceptCalls)
    }

    @Test
    fun `new incoming screening clears an unaccepted arm`() {
        arm("5551234567", 1)

        AnswerHangUpController.onIncomingScreeningStarted()

        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `numberless ringing duplicate does not accept but numbered ringing does`() {
        arm("5551234567", 1)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        AnswerHangUpReceiver().onReceive(
            context,
            Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
            },
        )
        assertEquals(0, callControl.audioOnlyAcceptCalls)

        AnswerHangUpReceiver().onReceive(
            context,
            Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, "5551234567")
            },
        )
        assertEquals(1, callControl.audioOnlyAcceptCalls)
    }

    @Test
    fun `extra present empty number accepts a withheld call`() {
        arm(null, 1)

        AnswerHangUpController.onPhoneState(
            state = TelephonyManager.EXTRA_STATE_RINGING,
            incomingNumber = null,
            hasIncomingNumber = true,
        )

        assertEquals(1, callControl.audioOnlyAcceptCalls)
    }

    @Test
    fun `outgoing call clears every phase without ending a call`() {
        arm("5551234567", 1)
        AnswerHangUpController.onOutgoingCallStarted()
        assertFalse(AnswerHangUpController.hasPendingCall())

        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onOutgoingCallStarted()
        assertFalse(AnswerHangUpController.hasPendingCall())

        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        AnswerHangUpController.onOutgoingCallStarted()
        assertFalse(AnswerHangUpController.hasPendingCall())
        assertEquals(0, callControl.endCalls)
    }

    @Test
    fun `try arm refuses while answering or hanging up`() {
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        assertFalse(AnswerHangUpController.tryArm("5557654321", 1))
        assertEquals(AnswerHangUpController.Phase.ANSWERING, AnswerHangUpController.phaseForTest())

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        assertFalse(AnswerHangUpController.tryArm("5557654321", 1))
        assertEquals(AnswerHangUpController.Phase.HANGING_UP, AnswerHangUpController.phaseForTest())
    }

    @Test
    fun `call control runtime exceptions clear without propagating`() {
        callControl.throwOnAccept = true
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        assertFalse(AnswerHangUpController.hasPendingCall())

        callControl.throwOnAccept = false
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        callControl.throwOnStateRead = true
        scheduler.advanceBy(250)
        assertFalse(AnswerHangUpController.hasPendingCall())

        callControl.throwOnStateRead = false
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        callControl.throwOnEnd = true
        repeat(4) { scheduler.advanceBy(250) }
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `receiver ignores an action other than phone state`() {
        arm("5551234567", 1)
        val intent =
            Intent("com.sysadmindoc.callshield.UNRELATED").apply {
                putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, "5551234567")
            }

        AnswerHangUpReceiver().onReceive(ApplicationProvider.getApplicationContext(), intent)

        assertEquals(0, callControl.audioOnlyAcceptCalls)
        assertEquals(AnswerHangUpController.Phase.ARMED, AnswerHangUpController.phaseForTest())
    }

    @Test
    fun `delay clamp enforces supported range`() {
        assertEquals(1, AnswerHangUpController.clampDelaySeconds(0))
        assertEquals(10, AnswerHangUpController.clampDelaySeconds(99))
        assertEquals(1, AnswerHangUpController.clampDelaySeconds(null))
    }

    private fun arm(
        number: String?,
        delaySeconds: Int,
    ) {
        assertTrue(AnswerHangUpController.tryArm(number, delaySeconds))
    }

    private class FakeClock {
        private var currentMillis = 0L

        fun now(): Long = currentMillis

        fun advanceBy(millis: Long) {
            currentMillis += millis
        }
    }

    private class FakeCallControl : AnswerHangUpController.CallControl {
        var audioOnlyAcceptCalls = 0
        var endCalls = 0
        var currentState = TelephonyManager.CALL_STATE_RINGING
        var throwOnAccept = false
        var throwOnEnd = false
        var throwOnStateRead = false

        override fun acceptRingingCallAudioOnly(): Boolean {
            if (throwOnAccept) error("accept")
            audioOnlyAcceptCalls += 1
            return true
        }

        override fun endCall(): Boolean {
            if (throwOnEnd) error("end")
            endCalls += 1
            return true
        }

        override fun currentCallState(): Int {
            if (throwOnStateRead) error("state")
            return currentState
        }
    }

    private class FakeScheduler(
        private val clock: FakeClock,
    ) : AnswerHangUpController.Scheduler {
        private data class ScheduledTask(
            val dueAt: Long,
            val task: () -> Unit,
        )

        private val scheduled = mutableListOf<ScheduledTask>()

        override fun postDelayed(
            delayMillis: Long,
            task: () -> Unit,
        ) {
            scheduled += ScheduledTask(clock.now() + delayMillis, task)
        }

        override fun cancelAll() {
            scheduled.clear()
        }

        fun advanceBy(millis: Long) {
            clock.advanceBy(millis)
            runDueTasks()
        }

        private fun runDueTasks() {
            while (true) {
                val next = scheduled.filter { it.dueAt <= clock.now() }.minByOrNull { it.dueAt } ?: return
                scheduled.remove(next)
                next.task()
            }
        }
    }
}
