package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Number details showed the importers' raw field names and the same complaint
 * twice for bundled number +13152328257.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceDescriptionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The stored description of +13152328257 in database v49.
    private val stored =
        "FCC: Unwanted Calls; FCC callback_business: Unwanted Calls (Live Voice); " +
            "FCC caller_id: Unwanted Calls; FTC caller ID: Computer  & technical support"

    @Test
    fun `complaints read as words, one line per kind, without repeats`() {
        assertEquals(
            listOf(
                "FCC complaints about the caller ID: Unwanted Calls",
                "FCC complaints giving it as the number to call back: Unwanted Calls (Live Voice)",
                "FTC complaints about the caller ID: Computer & technical support",
            ),
            SourceDescriptions.readable(context, stored).lines(),
        )
    }

    @Test
    fun `no raw field name survives, and formatting twice changes nothing`() {
        val once = SourceDescriptions.readable(context, stored)

        assertFalse(once, once.contains("caller_id") || once.contains("callback_business"))
        assertEquals(once, SourceDescriptions.readable(context, once))
    }

    @Test
    fun `other sources pass through once each`() {
        assertEquals(
            listOf("FCC complaints: Robocalls", "Community reported", "Saracroche: Opérateur"),
            SourceDescriptions.readable(context, "FCC: Robocalls; Community reported; Saracroche: Opérateur; Community reported").lines(),
        )
        assertEquals("", SourceDescriptions.readable(context, ""))
    }

    @Test
    fun `the block explanation lists the complaints instead of the raw text`() {
        val reasoning = BlockReasoning.explain(context, reasonCode = BlockReasonCode.DATABASE, description = stored, confidence = 100)

        assertEquals(SourceDescriptions.readable(context, stored).lines(), reasoning.bullets)
    }
}
