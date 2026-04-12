package com.sysadmindoc.callshield.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end behavior of CrashReporter on a real Android runtime.
 *
 * We don't trigger uncaught exceptions here (that would crash the test
 * process); instead we exercise the same file IO paths the uncaught
 * handler would take — persisting a report, reading it back, sharing via
 * FileProvider, and clearing. The private `persistCrash` call is invoked
 * via reflection so we stay on the real write path instead of duplicating
 * it in the test.
 */
@RunWith(AndroidJUnit4::class)
class CrashReporterInstrumentedTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        CrashReporter.clearAll(ctx)
    }

    @Test
    fun latestCrash_is_null_when_no_crashes_recorded() {
        CrashReporter.clearAll(ctx)
        assertNull(CrashReporter.latestCrash(ctx))
    }

    @Test
    fun shareLatestCrashIntent_is_null_when_no_crashes_recorded() {
        CrashReporter.clearAll(ctx)
        assertNull(CrashReporter.shareLatestCrashIntent(ctx))
    }

    @Test
    fun persistCrash_writes_a_readable_report_and_exposes_share_intent() {
        CrashReporter.clearAll(ctx)
        invokePersist(RuntimeException("synthetic test crash"))

        val latest = CrashReporter.latestCrash(ctx)
        assertNotNull("latest crash should exist after persist", latest)
        val content = latest!!.readText()
        assertTrue("contains our exception message", content.contains("synthetic test crash"))
        assertTrue("contains header", content.contains("CallShield crash report"))
        assertTrue("contains versionName", content.contains("versionName:"))

        val intent = CrashReporter.shareLatestCrashIntent(ctx)
        assertNotNull("share intent should be available", intent)
        assertEquals("text/plain", intent!!.type)
        // Use the type-safe overload on API 33+, fall back on older.
        val streamUri: android.net.Uri? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            }
        assertNotNull(streamUri)
    }

    @Test
    fun clearAll_removes_every_persisted_report() {
        invokePersist(IllegalStateException("one"))
        invokePersist(IllegalStateException("two"))
        assertNotNull(CrashReporter.latestCrash(ctx))

        CrashReporter.clearAll(ctx)
        assertNull(CrashReporter.latestCrash(ctx))
        val dir = java.io.File(ctx.filesDir, "crashes")
        assertFalse(
            "no crash_*.txt files should remain",
            dir.exists() && dir.listFiles()?.any { it.name.startsWith("crash_") } == true
        )
    }

    private fun invokePersist(t: Throwable) {
        val method = CrashReporter::class.java
            .getDeclaredMethod("persistCrash", android.content.Context::class.java, Thread::class.java, Throwable::class.java)
            .apply { isAccessible = true }
        method.invoke(CrashReporter, ctx, Thread.currentThread(), t)
    }
}
