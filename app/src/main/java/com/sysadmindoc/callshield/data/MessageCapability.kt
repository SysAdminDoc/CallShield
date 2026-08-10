package com.sysadmindoc.callshield.data

/** Message ingress path whose content availability was observed. */
internal enum class MessageCapabilitySource {
    SMS_BROADCAST,
    NOTIFICATION_LISTENER,
}

/**
 * Evidence availability, deliberately separate from a spam/clean verdict.
 * Missing message text must never be represented as a clean result.
 */
internal enum class MessageCapabilityState {
    NOT_OBSERVED,
    FULL_CONTENT,
    SENDER_ONLY,
    BODY_REDACTED,
    DELAYED,
    UNSUPPORTED,
}

/**
 * Privacy-safe latest capability observation. No sender, body, URL, or
 * notification package is retained here.
 */
internal data class MessageCapabilityStatus(
    val source: MessageCapabilitySource,
    val state: MessageCapabilityState,
    val apiLevel: Int,
    val observedAtMillis: Long = 0L,
    val latencyMillis: Long? = null,
) {
    val isDegraded: Boolean
        get() = state != MessageCapabilityState.NOT_OBSERVED && state != MessageCapabilityState.FULL_CONTENT

    val hasContentEvidence: Boolean
        get() = state == MessageCapabilityState.FULL_CONTENT

    /** Android 16 stopped promising global ordering for SMS broadcasts. */
    val smsOrderingAdvisory: Boolean
        get() = source == MessageCapabilitySource.SMS_BROADCAST && apiLevel >= MessageCapabilityDetector.ANDROID_16_API

    /** Fields safe to include in diagnostics/logcat; never add message data here. */
    fun privacySafeLogLine(): String =
        "source=${source.name.lowercase()} state=${state.name.lowercase()} " +
            "api=$apiLevel latencyMs=${latencyMillis ?: -1L}"

    companion object {
        fun notObserved(
            source: MessageCapabilitySource,
            apiLevel: Int,
        ): MessageCapabilityStatus =
            MessageCapabilityStatus(
                source = source,
                state = MessageCapabilityState.NOT_OBSERVED,
                apiLevel = apiLevel,
            )

        fun decode(
            source: MessageCapabilitySource,
            stateName: String?,
            apiLevel: Int?,
            observedAtMillis: Long?,
            latencyMillis: Long?,
            currentApiLevel: Int,
        ): MessageCapabilityStatus {
            val state =
                stateName
                    ?.let { raw -> runCatching { MessageCapabilityState.valueOf(raw) }.getOrNull() }
                    ?: MessageCapabilityState.NOT_OBSERVED
            return MessageCapabilityStatus(
                source = source,
                state = state,
                apiLevel = apiLevel?.coerceIn(1, 1_000) ?: currentApiLevel,
                observedAtMillis = observedAtMillis?.coerceAtLeast(0L) ?: 0L,
                latencyMillis = latencyMillis?.coerceAtLeast(0L),
            )
        }
    }
}

/** Pure capability classification shared by the direct SMS and notification paths. */
internal object MessageCapabilityDetector {
    const val ANDROID_15_API = 35
    const val ANDROID_16_API = 36
    const val DELAYED_MESSAGE_THRESHOLD_MILLIS = 30_000L
    private const val MAX_TRACKED_LATENCY_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    fun classifySmsBroadcast(
        apiLevel: Int,
        messagesDelivered: Boolean,
        senderPresent: Boolean,
        bodyPresent: Boolean,
        latencyMillis: Long? = null,
        observedAtMillis: Long = System.currentTimeMillis(),
    ): MessageCapabilityStatus =
        MessageCapabilityStatus(
            source = MessageCapabilitySource.SMS_BROADCAST,
            state =
                when {
                    !messagesDelivered || !senderPresent -> MessageCapabilityState.UNSUPPORTED
                    latencyMillis.isDelayed() -> MessageCapabilityState.DELAYED
                    !bodyPresent -> MessageCapabilityState.SENDER_ONLY
                    else -> MessageCapabilityState.FULL_CONTENT
                },
            apiLevel = apiLevel,
            observedAtMillis = observedAtMillis,
            latencyMillis = latencyMillis.sanitizeLatency(),
        )

    fun classifyNotification(
        apiLevel: Int,
        notificationAccessGranted: Boolean,
        senderPresent: Boolean,
        bodyPresent: Boolean,
        redactionSuspected: Boolean,
        latencyMillis: Long? = null,
        observedAtMillis: Long = System.currentTimeMillis(),
    ): MessageCapabilityStatus =
        MessageCapabilityStatus(
            source = MessageCapabilitySource.NOTIFICATION_LISTENER,
            state =
                when {
                    !notificationAccessGranted || !senderPresent -> {
                        MessageCapabilityState.UNSUPPORTED
                    }

                    latencyMillis.isDelayed() -> {
                        MessageCapabilityState.DELAYED
                    }

                    redactionSuspected || (apiLevel >= ANDROID_15_API && !bodyPresent) -> {
                        MessageCapabilityState.BODY_REDACTED
                    }

                    !bodyPresent -> {
                        MessageCapabilityState.SENDER_ONLY
                    }

                    else -> {
                        MessageCapabilityState.FULL_CONTENT
                    }
                },
            apiLevel = apiLevel,
            observedAtMillis = observedAtMillis,
            latencyMillis = latencyMillis.sanitizeLatency(),
        )

    fun latencyMillis(
        sourceTimestampMillis: Long,
        observedAtMillis: Long,
    ): Long? =
        sourceTimestampMillis
            .takeIf { it > 0L && observedAtMillis >= it }
            ?.let { (observedAtMillis - it).sanitizeLatency() }

    private fun Long?.isDelayed(): Boolean = this != null && this >= DELAYED_MESSAGE_THRESHOLD_MILLIS

    private fun Long.sanitizeLatency(): Long = coerceIn(0L, MAX_TRACKED_LATENCY_MILLIS)

    private fun Long?.sanitizeLatency(): Long? = this?.sanitizeLatency()
}
