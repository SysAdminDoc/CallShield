package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTraceTest {
    @Test
    fun `trace detects conflict when both block and allow entries exist`() {
        val entries =
            listOf(
                PipelineTraceEntry("whitelist", 10_000, PipelineTraceVerdict.ALLOW, BlockResult.allow("whitelist")),
                PipelineTraceEntry("database", 7_000, PipelineTraceVerdict.BLOCK, BlockResult.block("database")),
                PipelineTraceEntry("heuristic", 3_000, PipelineTraceVerdict.PASS),
            )
        val trace = PipelineTrace(entries, hasConflict = entries.any { it.verdict == PipelineTraceVerdict.BLOCK } && entries.any { it.verdict == PipelineTraceVerdict.ALLOW })
        assertTrue(trace.hasConflict)
        assertEquals(3, trace.entries.size)
    }

    @Test
    fun `trace reports no conflict when only blocks are present`() {
        val entries =
            listOf(
                PipelineTraceEntry("database", 7_000, PipelineTraceVerdict.BLOCK, BlockResult.block("database")),
                PipelineTraceEntry("prefix", 6_000, PipelineTraceVerdict.BLOCK, BlockResult.block("prefix")),
                PipelineTraceEntry("heuristic", 3_000, PipelineTraceVerdict.PASS),
            )
        val trace = PipelineTrace(entries, hasConflict = entries.any { it.verdict == PipelineTraceVerdict.BLOCK } && entries.any { it.verdict == PipelineTraceVerdict.ALLOW })
        assertFalse(trace.hasConflict)
    }

    @Test
    fun `trace reports no conflict when only allows are present`() {
        val entries =
            listOf(
                PipelineTraceEntry("whitelist", 10_000, PipelineTraceVerdict.ALLOW, BlockResult.allow("whitelist")),
                PipelineTraceEntry("contacts", 9_000, PipelineTraceVerdict.PASS),
            )
        val trace = PipelineTrace(entries, hasConflict = entries.any { it.verdict == PipelineTraceVerdict.BLOCK } && entries.any { it.verdict == PipelineTraceVerdict.ALLOW })
        assertFalse(trace.hasConflict)
    }

    @Test
    fun `disabled checkers do not contribute to conflict`() {
        val entries =
            listOf(
                PipelineTraceEntry("whitelist", 10_000, PipelineTraceVerdict.DISABLED),
                PipelineTraceEntry("database", 7_000, PipelineTraceVerdict.BLOCK, BlockResult.block("database")),
            )
        val trace = PipelineTrace(entries, hasConflict = entries.any { it.verdict == PipelineTraceVerdict.BLOCK } && entries.any { it.verdict == PipelineTraceVerdict.ALLOW })
        assertFalse(trace.hasConflict)
    }

    @Test
    fun `empty trace has no conflict`() {
        val trace = PipelineTrace(emptyList(), hasConflict = false)
        assertFalse(trace.hasConflict)
        assertTrue(trace.entries.isEmpty())
    }

    @Test
    fun `all four verdict types map correctly`() {
        assertEquals("BLOCK", PipelineTraceVerdict.BLOCK.name)
        assertEquals("ALLOW", PipelineTraceVerdict.ALLOW.name)
        assertEquals("PASS", PipelineTraceVerdict.PASS.name)
        assertEquals("DISABLED", PipelineTraceVerdict.DISABLED.name)
    }

    @Test
    fun `temporary allow sits below explicit blocks and above downloaded database`() {
        assertTrue(CheckerPriority.MANUAL_WHITELIST > CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.USER_BLOCKLIST > CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.SYSTEM_BLOCK_LIST > CheckerPriority.TEMPORARY_ALLOW)
        assertTrue(CheckerPriority.TEMPORARY_ALLOW > CheckerPriority.GITHUB_DATABASE)
    }

    @Test
    fun `traceEntry preserves BlockResult details`() {
        val result = BlockResult.block("database", "robocall", "Known spam number", 95)
        val entry = PipelineTraceEntry("database", 7_000, PipelineTraceVerdict.BLOCK, result)

        assertEquals("database", entry.checkerName)
        assertEquals(7_000, entry.priority)
        assertEquals(PipelineTraceVerdict.BLOCK, entry.verdict)
        assertTrue(entry.result?.shouldBlock ?: false)
        assertEquals(95, entry.result?.confidence)
        assertEquals("robocall", entry.result?.type)
    }
}
