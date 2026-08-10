package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [CheckerPipeline.run] — the actual 5-second-deadline
 * hot path. Verifies deadline short-circuit, first-non-null-wins ordering,
 * exception tolerance, and the disabled-checker skip, none of which the
 * diagnostic [CheckerPipeline.traceAll] path (already covered) exercises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckerPipelineRunTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A checker that records whether it ran and returns a fixed verdict. */
    private class FakeChecker(
        override val priority: Int,
        override val name: String,
        private val enabled: Boolean = true,
        private val throwOnEnablement: Boolean = false,
        private val throwOnCheck: Boolean = false,
        private val result: BlockResult? = null,
    ) : IChecker {
        var checked = false
            private set

        override suspend fun isEnabled(ctx: CheckContext): Boolean {
            if (throwOnEnablement) error("enablement boom")
            return enabled
        }

        override suspend fun check(ctx: CheckContext): BlockResult? {
            checked = true
            if (throwOnCheck) error("boom")
            return result
        }
    }

    /** Deterministic monotonic clock: "now" is fixed at [elapsedMs] past entry. */
    private fun ctx(elapsedMs: Long = 0L) =
        CheckContext(
            appContext = context,
            number = "+15551230000",
            realtimeCall = true,
            prefs = emptyPreferences(),
            clock = { elapsedMs },
            startTimeMillis = 0L,
        )

    @Test
    fun `returns first non-null result and short-circuits lower checkers`() =
        runBlocking {
            val high = FakeChecker(9_000, "high", result = BlockResult.block("high"))
            val low = FakeChecker(1_000, "low", result = BlockResult.block("low"))

            val result = CheckerPipeline.run(listOf(high, low), ctx())

            assertEquals("high", result?.matchSource)
            assertTrue(high.checked)
            assertFalse("lower-priority checker must not run after a block", low.checked)
        }

    @Test
    fun `deadline short-circuit returns null and never invokes checkers`() =
        runBlocking {
            val wouldBlock = FakeChecker(9_000, "would_block", result = BlockResult.block("would_block"))
            // Clock fixed 10s past entry → timeLeftMillis() <= 0.
            val expired = ctx(elapsedMs = 10_000)

            val result = CheckerPipeline.run(listOf(wouldBlock), expired)

            assertNull("expired budget must fail open (null), not block", result)
            assertFalse(wouldBlock.checked)
        }

    @Test
    fun `diagnostic run identifies the stage and checkers skipped by the budget`() =
        runBlocking {
            val wouldBlock = FakeChecker(9_000, "would_block", result = BlockResult.block("would_block"))
            val expired = ctx(elapsedMs = 10_000)

            val run = CheckerPipeline.runWithDiagnostics(listOf(wouldBlock), expired)

            assertNull(run.result)
            assertTrue(run.diagnostics?.budgetExhausted == true)
            assertEquals("would_block", run.diagnostics?.cutoffChecker)
            assertEquals(listOf("would_block"), run.diagnostics?.unevaluatedCheckers)
            assertFalse(wouldBlock.checked)
        }

    @Test
    fun `slow checker produces one fail-open diagnostic before lower stages run`() =
        runBlocking {
            var elapsed = 0L
            val slow =
                object : IChecker {
                    override val priority = 9_000
                    override val name = "slow"

                    override suspend fun check(ctx: CheckContext): BlockResult? {
                        elapsed = 5_000L
                        return null
                    }
                }
            val lower = FakeChecker(1_000, "lower", result = BlockResult.block("lower"))
            val contextWithClock =
                CheckContext(
                    appContext = context,
                    number = "+15551230000",
                    realtimeCall = true,
                    prefs = emptyPreferences(),
                    clock = { elapsed },
                    startTimeMillis = 0L,
                )

            val run = CheckerPipeline.runWithDiagnostics(listOf(slow, lower), contextWithClock)

            assertNull(run.result)
            assertEquals(listOf("lower"), run.diagnostics?.unevaluatedCheckers)
            assertEquals("lower", run.diagnostics?.cutoffChecker)
            assertFalse(lower.checked)
        }

    @Test
    fun `checker exception is swallowed and the pipeline continues`() =
        runBlocking {
            val boom = FakeChecker(9_000, "boom", throwOnCheck = true)
            val next = FakeChecker(1_000, "next", result = BlockResult.block("next"))

            val result = CheckerPipeline.run(listOf(boom, next), ctx())

            assertEquals("next", result?.matchSource)
            assertTrue(boom.checked)
            assertTrue(next.checked)

            val diagnostic = CheckerPipeline.runWithDiagnostics(listOf(boom, next), ctx()).diagnostics
            assertEquals(listOf("boom"), diagnostic?.failedCheckers)
        }

    @Test
    fun `checker enablement exception is swallowed and the pipeline continues`() =
        runBlocking {
            val boom = FakeChecker(9_000, "boom", throwOnEnablement = true)
            val next = FakeChecker(1_000, "next", result = BlockResult.block("next"))

            val result = CheckerPipeline.run(listOf(boom, next), ctx())

            assertEquals("next", result?.matchSource)
            assertFalse(boom.checked)
            assertTrue(next.checked)
        }

    @Test
    fun `checker cancellation is propagated`() {
        val cancelled =
            object : IChecker {
                override val priority = 9_000
                override val name = "cancelled"

                override suspend fun isEnabled(ctx: CheckContext): Boolean = true

                override suspend fun check(ctx: CheckContext): BlockResult? = throw CancellationException("cancelled")
            }

        assertThrows(CancellationException::class.java) {
            runBlocking { CheckerPipeline.run(listOf(cancelled), ctx()) }
        }
    }

    @Test
    fun `disabled checker is skipped without invoking check`() =
        runBlocking {
            val disabled = FakeChecker(9_000, "disabled", enabled = false, result = BlockResult.block("disabled"))
            val enabled = FakeChecker(1_000, "enabled", result = BlockResult.block("enabled"))

            val result = CheckerPipeline.run(listOf(disabled, enabled), ctx())

            assertEquals("enabled", result?.matchSource)
            assertFalse(disabled.checked)
        }

    @Test
    fun `all-pass pipeline returns null`() =
        runBlocking {
            val a = FakeChecker(9_000, "a", result = null)
            val b = FakeChecker(1_000, "b", result = null)

            assertNull(CheckerPipeline.run(listOf(a, b), ctx()))
            assertTrue(a.checked)
            assertTrue(b.checked)
        }
}
