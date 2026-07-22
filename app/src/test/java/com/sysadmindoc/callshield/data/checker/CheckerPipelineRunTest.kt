package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        private val throwOnCheck: Boolean = false,
        private val result: BlockResult? = null,
    ) : IChecker {
        var checked = false
            private set

        override suspend fun isEnabled(ctx: CheckContext): Boolean = enabled

        override suspend fun check(ctx: CheckContext): BlockResult? {
            checked = true
            if (throwOnCheck) error("boom")
            return result
        }
    }

    private fun ctx(startTimeMillis: Long = System.currentTimeMillis()) =
        CheckContext(
            appContext = context,
            number = "+15551230000",
            realtimeCall = true,
            prefs = emptyPreferences(),
            startTimeMillis = startTimeMillis,
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
            // startTimeMillis 10s in the past → timeLeftMillis() <= 0.
            val expired = ctx(startTimeMillis = System.currentTimeMillis() - 10_000)

            val result = CheckerPipeline.run(listOf(wouldBlock), expired)

            assertNull("expired budget must fail open (null), not block", result)
            assertFalse(wouldBlock.checked)
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
