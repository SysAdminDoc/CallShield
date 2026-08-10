package com.sysadmindoc.callshield.service

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures a screening callback receives at most one Telecom response. */
internal class ScreeningResponseGate<T>(
    private val responder: (T) -> Unit,
) {
    private val sent = AtomicBoolean(false)

    val hasResponded: Boolean
        get() = sent.get()

    fun respond(response: T) {
        if (sent.compareAndSet(false, true)) {
            responder(response)
        }
    }
}
