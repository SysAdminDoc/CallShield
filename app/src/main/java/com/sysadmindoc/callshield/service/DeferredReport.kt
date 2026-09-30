package com.sysadmindoc.callshield.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A community report held back while the overlay offers Undo on a block. It
 * sends once [wait] returns unless [cancel] got there first; a report already
 * on its way can't be recalled, so [cancel] then returns false.
 */
internal class DeferredReport(
    scope: CoroutineScope,
    private val wait: suspend () -> Unit,
    private val send: suspend () -> Unit,
) {
    private val state = AtomicInteger(PENDING)

    internal val job: Job =
        scope.launch {
            wait()
            if (state.compareAndSet(PENDING, SENDING)) send()
        }

    /** True when the report was still waiting and now won't go out. */
    fun cancel(): Boolean {
        val stopped = state.compareAndSet(PENDING, CANCELLED)
        if (stopped) job.cancel()
        return stopped
    }

    private companion object {
        const val PENDING = 0
        const val SENDING = 1
        const val CANCELLED = 2
    }
}
