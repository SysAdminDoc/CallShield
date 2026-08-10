package com.sysadmindoc.callshield.ui.screens.more

import com.sysadmindoc.callshield.data.MessageCapabilitySource
import com.sysadmindoc.callshield.data.MessageCapabilityState
import com.sysadmindoc.callshield.data.MessageCapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCapabilityUiModelTest {
    @Test
    fun `redacted content is rendered as degraded`() {
        val ui =
            messageCapabilityUiState(
                MessageCapabilityStatus(
                    source = MessageCapabilitySource.NOTIFICATION_LISTENER,
                    state = MessageCapabilityState.BODY_REDACTED,
                    apiLevel = 35,
                ),
            )

        assertEquals(MessageCapabilityUiSeverity.Degraded, ui.severity)
        assertFalse(ui.passed)
    }

    @Test
    fun `delayed content is rendered as degraded`() {
        val ui =
            messageCapabilityUiState(
                MessageCapabilityStatus(
                    source = MessageCapabilitySource.SMS_BROADCAST,
                    state = MessageCapabilityState.DELAYED,
                    apiLevel = 36,
                ),
            )

        assertEquals(MessageCapabilityUiSeverity.Degraded, ui.severity)
        assertFalse(ui.passed)
    }

    @Test
    fun `unobserved capability is informational rather than clean`() {
        val ui =
            messageCapabilityUiState(
                MessageCapabilityStatus.notObserved(MessageCapabilitySource.SMS_BROADCAST, 37),
            )

        assertEquals(MessageCapabilityUiSeverity.Informational, ui.severity)
        assertTrue(ui.passed)
    }

    @Test
    fun `full SMS content on Android 16 remains an advisory`() {
        val ui =
            messageCapabilityUiState(
                MessageCapabilityStatus(
                    source = MessageCapabilitySource.SMS_BROADCAST,
                    state = MessageCapabilityState.FULL_CONTENT,
                    apiLevel = 36,
                ),
            )

        assertEquals(MessageCapabilityUiSeverity.Informational, ui.severity)
        assertTrue(ui.passed)
    }
}
