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
    fun `all five verdict types map correctly`() {
        assertEquals("BLOCK", PipelineTraceVerdict.BLOCK.name)
        assertEquals("ALLOW", PipelineTraceVerdict.ALLOW.name)
        assertEquals("PASS", PipelineTraceVerdict.PASS.name)
        assertEquals("DISABLED", PipelineTraceVerdict.DISABLED.name)
        assertEquals("ERROR", PipelineTraceVerdict.ERROR.name)
    }

    @Test
    fun `temporary allow sits below explicit blocks and above downloaded database`() {
        assertTrue(CheckerPriority.MANUAL_WHITELIST > CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.USER_BLOCKLIST > CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.SYSTEM_BLOCK_LIST > CheckerPriority.TEMPORARY_ALLOW)
        assertTrue(CheckerPriority.TEMPORARY_ALLOW > CheckerPriority.GITHUB_DATABASE)
    }

    @Test
    fun `temporary allow recovers prefix-blocked callers but never overrides user rules`() {
        // spam_prefixes has no user-facing creation path — it is downloaded
        // reputation data (whole country codes included). "Allow temporarily"
        // from the Blocked Log must therefore beat it, or the recovery action
        // silently does nothing for a prefix-blocked relative abroad.
        assertTrue(CheckerPriority.TEMPORARY_ALLOW > CheckerPriority.PREFIX_MATCH)
        // Explicit user rules stay authoritative over a temporary allow.
        assertTrue(CheckerPriority.WILDCARD_RULE > CheckerPriority.TEMPORARY_ALLOW)
        assertTrue(CheckerPriority.HASH_WILDCARD_RULE > CheckerPriority.TEMPORARY_ALLOW)
        assertTrue(CheckerPriority.USER_BLOCKLIST > CheckerPriority.TEMPORARY_ALLOW)
        // SIM-box hardening: carrier attestation must not bypass the
        // categorical prefix feed (see StirShakenTrustCheckerTest).
        assertTrue(CheckerPriority.PREFIX_MATCH > CheckerPriority.STIR_SHAKEN_TRUSTED)
        // Downloaded data stays below every explicit-allow recovery path.
        assertTrue(CheckerPriority.PREFIX_MATCH > CheckerPriority.GITHUB_DATABASE)
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
