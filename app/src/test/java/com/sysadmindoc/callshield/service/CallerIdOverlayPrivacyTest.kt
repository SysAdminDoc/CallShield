package com.sysadmindoc.callshield.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdOverlayPrivacyTest {
    @Test
    fun `live caller enrichment is disabled without opt in`() {
        assertFalse(shouldRunLiveCallerEnrichment(confidence = 45, optedIn = false))
    }

    @Test
    fun `live caller enrichment rejects clean local calls even when enabled`() {
        assertFalse(shouldRunLiveCallerEnrichment(confidence = 0, optedIn = true))
    }

    @Test
    fun `live caller enrichment accepts opted in locally suspicious calls`() {
        assertTrue(shouldRunLiveCallerEnrichment(confidence = 45, optedIn = true))
    }
}
