package com.sysadmindoc.callshield.data.checker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.checker.CarrierScamLabelChecker.Companion.carrierScamLabel
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Singapore, Ireland and Australia rewrite an unregistered SMS sender ID to a
 * warning label, and a text under one of those labels is flagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CarrierScamLabelCheckerTest {
    private val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `each carrier label is recognised whatever its case`() {
        assertEquals("Likely-SCAM", carrierScamLabel("Likely-SCAM", "SG"))
        assertEquals("Likely-SCAM", carrierScamLabel("likely-scam", null))
        assertEquals("Likely Scam", carrierScamLabel("LIKELY SCAM", "IE"))
        assertEquals("Unverified", carrierScamLabel("Unverified", "AU"))
        assertEquals("Unverified", carrierScamLabel("UNVERIFIED", "au"))
    }

    @Test
    fun `Unverified counts only on an Australian SIM`() {
        assertNull(carrierScamLabel("Unverified", "US"))
        assertNull(carrierScamLabel("Unverified", null))
    }

    @Test
    fun `an ordinary sender name or a near miss is not a label`() {
        assertNull(carrierScamLabel("ACMEBANK", "SG"))
        assertNull(carrierScamLabel("Likely-SCAMS", "SG"))
        assertNull(carrierScamLabel("Scam Alert", "IE"))
    }

    @Test
    fun `a text from a labelled sender is flagged with the carrier's label as the reason`() =
        runBlocking {
            val result = fixture.repository.isSpamSms("Likely-SCAM", "Your parcel is waiting, confirm your address")

            assertTrue(result.isSpam)
            assertEquals(BlockReasonCode.CARRIER_LABEL, result.reasonCode)
            assertTrue(result.description, result.description.contains("Likely-SCAM"))
        }

    @Test
    fun `the same text from an ordinary sender name is not flagged by the label`() =
        runBlocking {
            val result = fixture.repository.isSpamSms("ACMEBANK", "Your parcel is waiting, confirm your address")

            assertFalse(result.reasonCode == BlockReasonCode.CARRIER_LABEL)
        }
}
