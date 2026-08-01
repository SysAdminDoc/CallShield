package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsTrustOrderingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `outbound-trusted SMS bypasses shared content heuristic`() {
        val ctx = context(trusted = true)

        assertNull(HeuristicChecker.smsBodyForAnalysis(ctx))
        val verdict = runBlocking { SmsContextTrustChecker().check(ctx) }
        assertEquals("sms_context", verdict?.matchSource)
    }

    @Test
    fun `unknown SMS remains available to shared content heuristic`() {
        assertEquals(
            "urgent prize claim now",
            HeuristicChecker.smsBodyForAnalysis(context(trusted = false)),
        )
    }

    private fun context(trusted: Boolean) =
        CheckContext(
            appContext = context,
            number = "+15551234567",
            smsBody = "urgent prize claim now",
            realtimeCall = true,
            prefs = emptyPreferences(),
            smsContextTrusted = trusted,
        )
}
