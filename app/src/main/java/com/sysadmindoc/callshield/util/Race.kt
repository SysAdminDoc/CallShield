package com.sysadmindoc.callshield.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Race N suspend blocks, return the first result that satisfies [decisive],
 * cancel the losers.
 *
 * Pattern borrowed from SpamBlocker (`util/race.kt`) and adamff-dev's
 * `isSpamRace`. Used for reputation-API lookups where we want the first
 * "yes, spam" answer to win immediately while still tolerating slow/failed
 * competitors. All non-decisive results and all failures are treated as
 * "no opinion" — the race continues until someone decides or the timeout
 * fires.
 *
 * ## Contract
 *
 * - Every competitor runs in parallel on its own coroutine.
 * - First call to `[decisive] == true` wins — its result is returned and
 *   all other competitors are cancelled (structured cancellation via the
 *   parent [coroutineScope]).
 * - If every competitor completes without a decisive result, returns the
 *   default of type [T] — typically `null` or `false` depending on caller.
 * - If the timeout fires first, returns [onTimeout] (default `null`).
 * - Competitors that throw are counted as non-decisive failures; their
 *   exceptions are swallowed (this is a best-effort race, not a
 *   correctness-critical pipeline).
 *
 * ## Hot-path usage
 *
 * The caller supplies a [timeoutMillis] budget that has already had the
 * elapsed CallScreeningService time subtracted off. If the budget is <= 0
 * the race returns the timeout value immediately without launching any
 * competitors.
 *
 * @param competitors Work items. Each returns a [T] on completion.
 * @param timeoutMillis Hard deadline for the entire race.
 * @param decisive Predicate on a competitor's result — the first `true`
 *        return wins.
 * @param onTimeout Value returned when the timeout fires.
 * @param block Executed for each competitor. Should be cancellation-aware.
 */
suspend fun <C, T> race(
    competitors: List<C>,
    timeoutMillis: Long,
    decisive: (T) -> Boolean,
    onTimeout: T,
    block: suspend (C) -> T,
): T {
    if (timeoutMillis <= 0 || competitors.isEmpty()) return onTimeout

    // Channel.UNLIMITED: every competitor can publish without blocking even
    // if we've already received a decisive result (we cancel them via the
    // parent coroutineScope as soon as we pick a winner).
    val channel = Channel<T>(Channel.UNLIMITED)
    val remaining = AtomicInteger(competitors.size)

    // Private sealed outcome — keeps failure distinct from "a real value
    // the block happened to return". This prevents a thrown exception
    // from synthesizing `onTimeout` and winning the race just because
    // `decisive(onTimeout)` happens to be true for this caller.
    return coroutineScope {
        for (competitor in competitors) {
            launch {
                val outcome: Outcome<T> = try {
                    Outcome.Ok(block(competitor))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    Outcome.Failed
                }
                when (outcome) {
                    is Outcome.Ok -> {
                        if (decisive(outcome.value)) {
                            channel.trySend(outcome.value)
                        } else if (remaining.decrementAndGet() == 0) {
                            channel.trySend(onTimeout)
                        }
                    }
                    Outcome.Failed -> {
                        if (remaining.decrementAndGet() == 0) {
                            channel.trySend(onTimeout)
                        }
                    }
                }
            }
        }

        val winner = withTimeoutOrNull(timeoutMillis) {
            select<T> { channel.onReceive { it } }
        } ?: onTimeout

        // Cancel any survivors — structured concurrency via coroutineScope
        // means returning from the scope cancels children automatically.
        channel.close()
        winner
    }
}

/**
 * Internal outcome wrapper used by [race] to keep "failure" distinct
 * from "the block returned onTimeout." Callers never see this type.
 */
private sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data object Failed : Outcome<Nothing>
}

/**
 * Simpler wrapper: race N boolean-returning tasks, return `true` if any
 * of them answers `true` before the deadline.
 */
suspend fun <C> raceAny(
    competitors: List<C>,
    timeoutMillis: Long,
    block: suspend (C) -> Boolean,
): Boolean = race(
    competitors = competitors,
    timeoutMillis = timeoutMillis,
    decisive = { it },
    onTimeout = false,
    block = block,
)
