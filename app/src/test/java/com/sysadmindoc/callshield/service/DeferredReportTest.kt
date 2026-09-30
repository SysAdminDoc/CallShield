package com.sysadmindoc.callshield.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredReportTest {
    @Test
    fun `the report waits for the undo window`() =
        runBlocking {
            val window = CompletableDeferred<Unit>()
            var sent = 0
            val report = DeferredReport(this, wait = { window.await() }, send = { sent++ })

            assertEquals(0, sent)
            window.complete(Unit)
            report.job.join()

            assertEquals(1, sent)
            assertFalse(report.cancel())
        }

    @Test
    fun `undo inside the window stops the report`() =
        runBlocking {
            val window = CompletableDeferred<Unit>()
            var sent = 0
            val report = DeferredReport(this, wait = { window.await() }, send = { sent++ })

            assertTrue(report.cancel())
            window.complete(Unit)
            report.job.join()

            assertEquals(0, sent)
            assertFalse(report.cancel())
        }
}
