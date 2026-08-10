package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCapabilityDetectorTest {
    @Test
    fun `full SMS content is distinct from a clean verdict`() {
        val status =
            MessageCapabilityDetector.classifySmsBroadcast(
                apiLevel = 35,
                messagesDelivered = true,
                senderPresent = true,
                bodyPresent = true,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.FULL_CONTENT, status.state)
        assertTrue(status.hasContentEvidence)
        assertFalse(status.isDegraded)
    }

    @Test
    fun `sender-only SMS is degraded without being marked clean`() {
        val status =
            MessageCapabilityDetector.classifySmsBroadcast(
                apiLevel = 35,
                messagesDelivered = true,
                senderPresent = true,
                bodyPresent = false,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.SENDER_ONLY, status.state)
        assertFalse(status.hasContentEvidence)
        assertTrue(status.isDegraded)
    }

    @Test
    fun `missing SMS delivery is unsupported`() {
        val status =
            MessageCapabilityDetector.classifySmsBroadcast(
                apiLevel = 36,
                messagesDelivered = false,
                senderPresent = false,
                bodyPresent = false,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.UNSUPPORTED, status.state)
        assertTrue(status.smsOrderingAdvisory)
    }

    @Test
    fun `late SMS delivery is degraded as delayed`() {
        val status =
            MessageCapabilityDetector.classifySmsBroadcast(
                apiLevel = 36,
                messagesDelivered = true,
                senderPresent = true,
                bodyPresent = true,
                latencyMillis = MessageCapabilityDetector.DELAYED_MESSAGE_THRESHOLD_MILLIS,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.DELAYED, status.state)
        assertTrue(status.isDegraded)
        assertTrue(status.smsOrderingAdvisory)
    }

    @Test
    fun `Android 15 notification without body is redacted`() {
        val status =
            MessageCapabilityDetector.classifyNotification(
                apiLevel = MessageCapabilityDetector.ANDROID_15_API,
                notificationAccessGranted = true,
                senderPresent = true,
                bodyPresent = false,
                redactionSuspected = false,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.BODY_REDACTED, status.state)
        assertFalse(status.hasContentEvidence)
        assertTrue(status.isDegraded)
    }

    @Test
    fun `encrypted notification placeholder is redacted on older Android too`() {
        val status =
            MessageCapabilityDetector.classifyNotification(
                apiLevel = 34,
                notificationAccessGranted = true,
                senderPresent = true,
                bodyPresent = false,
                redactionSuspected = true,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.BODY_REDACTED, status.state)
    }

    @Test
    fun `notification access absence is unsupported`() {
        val status =
            MessageCapabilityDetector.classifyNotification(
                apiLevel = 37,
                notificationAccessGranted = false,
                senderPresent = true,
                bodyPresent = true,
                redactionSuspected = false,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.UNSUPPORTED, status.state)
    }

    @Test
    fun `notification delay takes precedence over available content`() {
        val status =
            MessageCapabilityDetector.classifyNotification(
                apiLevel = 37,
                notificationAccessGranted = true,
                senderPresent = true,
                bodyPresent = true,
                redactionSuspected = false,
                latencyMillis = MessageCapabilityDetector.DELAYED_MESSAGE_THRESHOLD_MILLIS + 1L,
                observedAtMillis = 1_000L,
            )

        assertEquals(MessageCapabilityState.DELAYED, status.state)
    }

    @Test
    fun `capability log line never includes message content`() {
        val logLine =
            MessageCapabilityStatus(
                source = MessageCapabilitySource.NOTIFICATION_LISTENER,
                state = MessageCapabilityState.BODY_REDACTED,
                apiLevel = 35,
                observedAtMillis = 1_000L,
            ).privacySafeLogLine()

        assertFalse(logLine.contains("message body", ignoreCase = true))
        assertFalse(logLine.contains("https", ignoreCase = true))
        assertTrue(logLine.contains("body_redacted"))
    }
}
