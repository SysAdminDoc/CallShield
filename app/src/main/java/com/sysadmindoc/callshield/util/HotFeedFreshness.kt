package com.sysadmindoc.callshield.util

import com.sysadmindoc.callshield.data.model.HotDataHealth
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Pure classifier for the three short-lived hot protection feeds.
 *
 * `HotDataSync` records only whether a refresh succeeded, so a publisher that
 * is perfectly reachable but has stopped producing looks exactly like a network
 * failure. That is not hypothetical: between 2026-08-24 and 2026-09-05 the
 * report queue stopped being consumed, all three feeds published `count: 0`
 * with an unchanging `generated` timestamp, and every device reported "hot data
 * unavailable" as though it could not reach GitHub.
 *
 * The feeds carry their own `generated` timestamp, which is what separates the
 * two: an unreachable feed has nothing to read, while a stalled publisher hands
 * over a perfectly good file whose timestamp has not moved in days.
 *
 * Deliberately pure — no Android, no clock, no I/O — so the states are
 * unit-testable and the UI just renders the result.
 */
object HotFeedFreshness {
    /**
     * How long a feed's own `generated` timestamp may sit unchanged before the
     * publisher is treated as stalled rather than quiet.
     *
     * The hot feeds regenerate with every merge and the sync cadence is 30
     * minutes, so a week without the timestamp moving is well past "nothing
     * trending this afternoon".
     */
    val DEFAULT_STALL_THRESHOLD_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

    enum class State {
        /** Reachable, and the publisher generated it recently. */
        CURRENT,

        /** Reachable, but the publisher has not regenerated it in a long time. */
        STALLED,

        /** Could not be fetched at all. */
        UNREACHABLE,
    }

    /**
     * Parse a feed's ISO-8601 `generated` value to epoch millis.
     *
     * Returns 0 for anything unparseable or absent. A feed that does not
     * declare when it was generated cannot be judged stalled, so callers treat
     * 0 as "no opinion" rather than "infinitely old".
     *
     * The publisher writes Python's `isoformat()`, a `+00:00` offset rather
     * than `Z`. `Instant.parse` only accepts offsets from Java 12 on, so on the
     * Java 11 based runtimes of Android 10 to 13 it would reject every real
     * feed; `OffsetDateTime` reads both forms everywhere.
     */
    fun publishedAtMillis(generated: String?): Long {
        val value = generated?.trim().orEmpty()
        if (value.isEmpty()) return 0L
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        } catch (_: ArithmeticException) {
            // A parseable year like +999999999 overflows epoch millis. The stamp
            // is persisted, so throwing here would break Protection Test until a
            // new one replaced it.
            0L
        }
    }

    /**
     * @param unreachable true when the feed could not be fetched. This wins over
     *   any timestamp: a file that never arrived tells us nothing about the
     *   publisher.
     * @param publishedAtMillis the feed's own `generated` value in epoch millis,
     *   or 0 when it is unknown.
     * @param now current epoch millis.
     */
    fun classify(
        unreachable: Boolean,
        publishedAtMillis: Long,
        now: Long,
        stallThresholdMillis: Long = DEFAULT_STALL_THRESHOLD_MILLIS,
    ): State {
        if (unreachable) return State.UNREACHABLE
        if (publishedAtMillis <= 0L) return State.CURRENT
        val age = now - publishedAtMillis
        // A clock skewed backwards, or a feed generated moments ago on a device
        // whose time is behind, is not evidence of a stalled publisher.
        if (age < 0L) return State.CURRENT
        return if (age > stallThresholdMillis) State.STALLED else State.CURRENT
    }

    /**
     * Worst state across the feeds, for a single summary row.
     *
     * Unreachable outranks stalled: it is the more actionable failure, and a
     * device that cannot fetch anything cannot know whether the publisher is
     * also stalled.
     */
    fun worst(states: Collection<State>): State =
        when {
            states.isEmpty() -> State.CURRENT
            states.contains(State.UNREACHABLE) -> State.UNREACHABLE
            states.contains(State.STALLED) -> State.STALLED
            else -> State.CURRENT
        }

    /**
     * Worst state across every feed the stored health knows about.
     *
     * A feed refused for arriving empty without `cleared` was reachable, so it
     * is judged by its own `generated` stamp. An install that has not refreshed
     * since `unreachableFeeds` was recorded has only `unavailableFeeds`, which
     * mixes both causes; it is read as unreachable until the next refresh.
     */
    fun stateOf(
        health: HotDataHealth,
        now: Long,
    ): State {
        val unreachable = health.unreachableFeeds ?: health.unavailableFeeds
        val feeds = unreachable + health.feedGeneratedAt.keys + health.clearedFeeds
        return worst(
            feeds.map { feed ->
                classify(
                    unreachable = feed in unreachable,
                    publishedAtMillis = publishedAtMillis(health.feedGeneratedAt[feed]),
                    now = now,
                )
            },
        )
    }
}
