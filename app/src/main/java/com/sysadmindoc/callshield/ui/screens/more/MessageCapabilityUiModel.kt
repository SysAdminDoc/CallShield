package com.sysadmindoc.callshield.ui.screens.more

import com.sysadmindoc.callshield.data.MessageCapabilityState
import com.sysadmindoc.callshield.data.MessageCapabilityStatus

internal enum class MessageCapabilityUiSeverity {
    Healthy,
    Informational,
    Degraded,
}

internal data class MessageCapabilityUiState(
    val severity: MessageCapabilityUiSeverity,
    val passed: Boolean,
)

internal fun messageCapabilityUiState(status: MessageCapabilityStatus): MessageCapabilityUiState =
    when {
        status.state == MessageCapabilityState.NOT_OBSERVED -> {
            MessageCapabilityUiState(
                severity = MessageCapabilityUiSeverity.Informational,
                passed = true,
            )
        }

        status.state == MessageCapabilityState.FULL_CONTENT && status.smsOrderingAdvisory -> {
            MessageCapabilityUiState(
                severity = MessageCapabilityUiSeverity.Informational,
                passed = true,
            )
        }

        status.state == MessageCapabilityState.FULL_CONTENT -> {
            MessageCapabilityUiState(
                severity = MessageCapabilityUiSeverity.Healthy,
                passed = true,
            )
        }

        else -> {
            MessageCapabilityUiState(
                severity = MessageCapabilityUiSeverity.Degraded,
                passed = false,
            )
        }
    }
