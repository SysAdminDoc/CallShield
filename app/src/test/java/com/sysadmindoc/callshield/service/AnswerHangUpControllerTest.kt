package com.sysadmindoc.callshield.service

import android.content.Intent
import android.media.AudioManager
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
    private lateinit var events: MutableList<String>
    private lateinit var callControl: FakeCallControl
    private lateinit var microphone: FakeMicrophone
    private lateinit var scheduler: FakeScheduler

    @Before
    fun setUp() {
        clock = FakeClock()
        events = mutableListOf()
        callControl = FakeCallControl(events)
        microphone = FakeMicrophone(events)
        scheduler = FakeScheduler(clock)
        AnswerHangUpController.setTestDependencies(clock::now, callControl, scheduler, microphone)
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
    fun `a national-format caller ID matches the number screening normalized`() {
        // Telecom handed screening 07911 123456, which it normalized to +447911123456,
        // and PHONE_STATE can carry either spelling.
        for (ringing in listOf("07911123456", "+447911123456")) {
            AnswerHangUpController.resetForTest()
            callControl = FakeCallControl()
            AnswerHangUpController.setTestDependencies(clock::now, callControl, scheduler)
            assertTrue(AnswerHangUpController.tryArm("07911 123456", 1, e164 = "+447911123456"))

            AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, ringing)

            assertEquals(ringing, 1, callControl.audioOnlyAcceptCalls)
        }
    }

    @Test
    fun `a call waiting during the delay still ends the spam call`() {
        arm("5551234567", 5)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        scheduler.advanceBy(1_000)

        // A second call reaches screening before it rings, while endCall() still means the spam call.
        AnswerHangUpController.onIncomingScreeningStarted()
        assertEquals(1, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())

        // Once it rings, nothing here may reject it.
        callControl.currentState = TelephonyManager.CALL_STATE_RINGING
        scheduler.advanceBy(10_000)
        assertEquals(1, callControl.endCalls)
    }

    @Test
    fun `a waiting call screening never saw is never rejected`() {
        arm("5551234567", 2)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_RINGING

        scheduler.advanceBy(5_000)

        assertEquals(0, callControl.endCalls)
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
    fun `a second spam call during the delay is answered and hung up after the first`() {
        connect("5551234567", 5)
        scheduler.advanceBy(1_000)

        // The second call reaches screening while the first is connected. The phone still
        // reads as on a call while the first one disconnects, and that call doesn't count.
        AnswerHangUpController.onIncomingScreeningStarted()
        assertTrue(AnswerHangUpController.isHandingOff())
        assertFalse(
            CallShieldScreeningService.answerHangUpBusy(
                controllerBusy = AnswerHangUpController.isBusy(),
                handingOff = AnswerHangUpController.isHandingOff(),
                telephonyOffhook = true,
                audioInCall = true,
            ),
        )
        arm("5557654321", 2)
        assertFalse(AnswerHangUpController.isHandingOff())

        // The first call going idle must not drop the second one's arm.
        callControl.currentState = TelephonyManager.CALL_STATE_IDLE
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertEquals(AnswerHangUpController.Phase.ARMED, AnswerHangUpController.phaseForTest())

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5557654321")
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5557654321")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        repeat(7) { scheduler.advanceBy(250) }
        assertEquals(1, callControl.endCalls)
        scheduler.advanceBy(250)

        assertEquals(listOf("accept", "mute", "end", "unmute", "accept", "mute", "end", "unmute"), events)
        assertFalse(AnswerHangUpController.hasPendingCall())
    }

    @Test
    fun `a caller who hangs up first cancels only their own hang-up`() {
        // An arm with no call ahead of it goes when the line goes idle.
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertFalse(AnswerHangUpController.hasPendingCall())

        // Behind a handed-off call, only the first caller is gone when the line first goes idle.
        connect("5551234567", 5)
        AnswerHangUpController.onIncomingScreeningStarted()
        arm("5557654321", 2)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertEquals(AnswerHangUpController.Phase.ARMED, AnswerHangUpController.phaseForTest())

        // The second caller gives up once their call has rung: their arm goes and nothing
        // else is ended.
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, null, hasIncomingNumber = false)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertFalse(AnswerHangUpController.hasPendingCall())
        assertEquals(1, callControl.audioOnlyAcceptCalls)
        assertEquals(1, callControl.endCalls)
    }

    @Test
    fun `the handoff ends with its window, the next screening or an outgoing call`() {
        connect("5551234567", 5)
        AnswerHangUpController.onIncomingScreeningStarted()
        assertTrue(AnswerHangUpController.isHandingOff())
        clock.advanceBy(5_000)
        assertFalse(AnswerHangUpController.isHandingOff())

        connect("5551234567", 5)
        AnswerHangUpController.onIncomingScreeningStarted()
        AnswerHangUpController.onIncomingScreeningStarted()
        assertFalse(AnswerHangUpController.isHandingOff())

        connect("5551234567", 5)
        AnswerHangUpController.onIncomingScreeningStarted()
        AnswerHangUpController.onOutgoingCallStarted()
        assertFalse(AnswerHangUpController.isHandingOff())
    }

    @Test
    fun `the microphone is muted only while the spam call is connected`() {
        arm("5551234567", 1)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "5551234567")
        // Telecom unmutes a call as it's answered, so the mute waits for offhook.
        assertFalse(microphone.micMuted)

        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "5551234567")
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        assertTrue(microphone.micMuted)
        assertTrue(microphone.heldMute)

        repeat(4) { scheduler.advanceBy(250) }

        assertEquals(listOf("accept", "mute", "end", "unmute"), events)
        assertFalse(microphone.micMuted)
        assertFalse(microphone.heldMute)
    }

    @Test
    fun `a microphone something else muted is left as it was`() {
        microphone.micMuted = true

        connect("5551234567", 1)
        repeat(4) { scheduler.advanceBy(250) }

        assertEquals(listOf("accept", "end"), events)
        assertTrue(microphone.micMuted)
    }

    @Test
    fun `every way out of the call puts the microphone back`() {
        val exits =
            listOf<Pair<String, () -> Unit>>(
                "caller hung up" to {
                    callControl.currentState = TelephonyManager.CALL_STATE_IDLE
                    scheduler.advanceBy(250)
                },
                "outgoing call" to { AnswerHangUpController.onOutgoingCallStarted() },
                "next screening" to { AnswerHangUpController.onIncomingScreeningStarted() },
                "state unreadable" to {
                    callControl.throwOnStateRead = true
                    scheduler.advanceBy(250)
                },
            )
        for ((name, exit) in exits) {
            connect("5551234567", 5)
            assertTrue(name, microphone.micMuted)

            exit()

            assertFalse(name, AnswerHangUpController.hasPendingCall())
            assertFalse(name, microphone.micMuted)
            assertFalse(name, microphone.heldMute)
        }
    }

    @Test
    fun `a spam call left connected for a waiting call stays muted until the line is idle`() {
        connect("5551234567", 5)
        callControl.currentState = TelephonyManager.CALL_STATE_RINGING
        scheduler.advanceBy(250)
        assertFalse(AnswerHangUpController.hasPendingCall())
        assertTrue(microphone.micMuted)
        assertTrue(AnswerHangUpController.wantsPhoneState())

        // An idle broadcast the phone has moved past changes nothing.
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertTrue(microphone.micMuted)

        callControl.currentState = TelephonyManager.CALL_STATE_IDLE
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertFalse(microphone.micMuted)
        assertFalse(AnswerHangUpController.wantsPhoneState())
        assertEquals(0, callControl.endCalls)
    }

    @Test
    fun `a new screening while an unscreened call rings leaves the spam call to its caller`() {
        connect("5551234567", 5)
        callControl.currentState = TelephonyManager.CALL_STATE_RINGING

        AnswerHangUpController.onIncomingScreeningStarted()

        assertEquals(0, callControl.endCalls)
        assertFalse(AnswerHangUpController.hasPendingCall())
        assertFalse(AnswerHangUpController.isHandingOff())
        assertTrue(microphone.micMuted)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a mute left by a process that died is undone on the next idle broadcast`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val record = context.getSharedPreferences("answer_hang_up", android.content.Context.MODE_PRIVATE)
        AnswerHangUpController.configure(context)
        AnswerHangUpController.setTestDependencies(clock::now, callControl, scheduler)
        connect("5551234567", 5)
        assertTrue(audioManager.isMicrophoneMute)
        assertTrue(record.getBoolean("mic_muted", false))

        // The process dies mid-call; a new one starts for the idle broadcast.
        AnswerHangUpController.resetForTest()
        callControl = FakeCallControl(events).apply { currentState = TelephonyManager.CALL_STATE_IDLE }
        AnswerHangUpController.setTestDependencies(clock::now, callControl, scheduler)
        assertTrue(audioManager.isMicrophoneMute)

        AnswerHangUpReceiver().onReceive(
            context,
            Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
            },
        )

        assertFalse(audioManager.isMicrophoneMute)
        assertFalse(record.getBoolean("mic_muted", false))
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

    /** Arms, answers and connects a spam call, leaving it waiting out its delay. */
    private fun connect(
        number: String,
        delaySeconds: Int,
    ) {
        arm(number, delaySeconds)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, number)
        AnswerHangUpController.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, number)
        callControl.currentState = TelephonyManager.CALL_STATE_OFFHOOK
        assertEquals(AnswerHangUpController.Phase.HANGING_UP, AnswerHangUpController.phaseForTest())
    }

    private class FakeClock {
        private var currentMillis = 0L

        fun now(): Long = currentMillis

        fun advanceBy(millis: Long) {
            currentMillis += millis
        }
    }

    private class FakeCallControl(
        private val events: MutableList<String> = mutableListOf(),
    ) : AnswerHangUpController.CallControl {
        var audioOnlyAcceptCalls = 0
        var endCalls = 0
        var currentState = TelephonyManager.CALL_STATE_RINGING
        var throwOnAccept = false
        var throwOnEnd = false
        var throwOnStateRead = false

        override fun acceptRingingCallAudioOnly(): Boolean {
            if (throwOnAccept) error("accept")
            audioOnlyAcceptCalls += 1
            events += "accept"
            return true
        }

        override fun endCall(): Boolean {
            if (throwOnEnd) error("end")
            endCalls += 1
            events += "end"
            return true
        }

        override fun currentCallState(): Int {
            if (throwOnStateRead) error("state")
            return currentState
        }
    }

    private class FakeMicrophone(
        private val events: MutableList<String>,
    ) : AnswerHangUpController.Microphone {
        var micMuted = false
        override var heldMute = false

        override fun isMuted(): Boolean = micMuted

        override fun setMuted(muted: Boolean) {
            micMuted = muted
            events += if (muted) "mute" else "unmute"
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
