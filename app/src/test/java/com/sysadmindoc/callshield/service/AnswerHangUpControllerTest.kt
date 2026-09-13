package com.sysadmindoc.callshield.service

import android.telephony.TelephonyManager
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
    fun `matching ringing accepts once and duplicate ringing is ignored`() {
        AnswerHangUpController.arm("5551234567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(1, callControl.acceptCalls)
        assertEquals(AnswerHangUpController.Phase.ANSWERING, AnswerHangUpController.phaseForTest())
    }

    @Test
    fun `different ringing number does not answer`() {
        AnswerHangUpController.arm("5551234567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5557654321")

        assertEquals(0, callControl.acceptCalls)
        assertTrue(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `ringing after arm expiry does not answer`() {
        AnswerHangUpController.arm("5551234567", 1)
        clock.advanceBy(5_000)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(0, callControl.acceptCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `offhook ends exactly once at the delay deadline`() {
        AnswerHangUpController.arm("5551234567", 2)
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
        AnswerHangUpController.arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        callControl.currentState = TelephonyManager.CALL_STATE_IDLE

        scheduler.advanceBy(2_000)

        assertEquals(0, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `ringing during hangup aborts without ending either call`() {
        AnswerHangUpController.arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5557654321")
        scheduler.advanceBy(2_000)

        assertEquals(0, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `polled offhook without broadcast still hangs up`() {
        AnswerHangUpController.arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK

        scheduler.advanceBy(250)
        repeat(4) { scheduler.advanceBy(250) }

        assertEquals(1, callControl.endCalls)
    }

    @Test
    fun `withheld number accepts null ringing number`() {
        AnswerHangUpController.arm(null, 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, null)

        assertEquals(1, callControl.acceptCalls)
    }

    @Test
    fun `north american prefix variants match on digits`() {
        AnswerHangUpController.arm("+1 (555) 123-4567", 1)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")

        assertEquals(1, callControl.acceptCalls)
    }

    @Test
    fun `new incoming screening clears an unaccepted arm`() {
        AnswerHangUpController.arm("5551234567", 1)

        AnswerHangUpController.onIncomingScreeningStarted()

        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `delay clamp enforces supported range`() {
        assertEquals(1, AnswerHangUpController.clampDelaySeconds(0))
        assertEquals(10, AnswerHangUpController.clampDelaySeconds(99))
        assertEquals(1, AnswerHangUpController.clampDelaySeconds(null))
    }

    private class FakeClock {
        private var currentMillis = 0L

        fun now(): Long = currentMillis

        fun advanceBy(millis: Long) {
            currentMillis += millis
        }
    }

    private class FakeCallControl : AnswerHangUpController.CallControl {
        var acceptCalls = 0
        var endCalls = 0
        var currentState = TelephonyManager.CALL_STATE_RINGING

        override fun acceptRingingCall() {
            acceptCalls += 1
        }

        override fun endCall(): Boolean {
            endCalls += 1
            return true
        }

        override fun currentCallState(): Int = currentState
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
