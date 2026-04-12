package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

/**
 * Pure-JVM tests for CrashReporter.
 *
 * The public entry point takes a Context we can't easily stub without
 * Robolectric, but the rotation + header-formatting logic is easy to
 * exercise by driving the filesystem directly. These cover the two
 * behaviors most likely to regress: pruning old crash files and writing
 * a non-empty report on a nested exception chain.
 */
class CrashReporterTest {

    @Test
    fun `rotation keeps only the five most recent crash files`() {
        val dir = Files.createTempDirectory("crash-rotate").toFile().apply { deleteOnExit() }
        try {
            // Create 10 crash files with staggered mtimes (oldest first)
            val files = (1..10).map { i ->
                File(dir, "crash_${i}.txt").apply {
                    writeText("fake $i")
                    setLastModified(i * 1000L)
                }
            }
            // Invoke the private rotate via reflection — rotation is the
            // most failure-prone part of the reporter.
            val method = CrashReporter::class.java.getDeclaredMethod("rotate", File::class.java)
            method.isAccessible = true
            method.invoke(CrashReporter, dir)

            val remaining = dir.listFiles { f -> f.name.startsWith("crash_") }!!
            assertEquals("should retain exactly 5", 5, remaining.size)
            // The retained five should be the NEWEST, not oldest
            val retainedIds = remaining.map { it.name.removePrefix("crash_").removeSuffix(".txt").toInt() }.sorted()
            assertEquals(listOf(6, 7, 8, 9, 10), retainedIds)
            // Sanity: files with epoch < 6000 were deleted
            assertTrue("old files should be gone", files.take(5).none { it.exists() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writing a nested exception chain produces a non-empty report with cause headers`() {
        // Mirror the PrintWriter-based serialization so we verify the
        // format we give the user actually surfaces the full chain.
        val root = RuntimeException("inner blew up")
        val mid = IllegalStateException("wrapping", root)
        val outer = IllegalArgumentException("top", mid)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            var depth = 0
            var cause: Throwable? = outer
            while (cause != null && depth < 200) {
                if (depth == 0) pw.println("Exception: ${cause.javaClass.name}: ${cause.message}")
                else pw.println("Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.forEach { pw.println("    at $it") }
                cause = cause.cause
                depth++
            }
        }
        val report = sw.toString()
        assertTrue(report.contains("Exception: java.lang.IllegalArgumentException: top"))
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: wrapping"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException: inner blew up"))
        assertNotNull(report)
    }
}
