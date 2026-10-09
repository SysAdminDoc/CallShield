package com.sysadmindoc.callshield.data.checker

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
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

    @Test
    fun `on a dual-SIM phone Unverified counts only when the Australian SIM in slot 2 received it`() =
        runBlocking {
            // Slot 1 is the American SIM the phone's home region comes from.
            val simCountries: Map<Int?, String> = mapOf(1 to "us", 2 to "au")
            IsolatedRepositoryFixture(
                ApplicationProvider.getApplicationContext(),
                checkerDependencies = CheckerDependencies(receivingSimCountry = { _, subscriptionId -> simCountries[subscriptionId] }),
            ).use { dualSim ->
                val onAustralianSim = dualSim.repository.isSpamSms("Unverified", APPOINTMENT, subscriptionId = 2)
                val onAmericanSim = dualSim.repository.isSpamSms("Unverified", APPOINTMENT, subscriptionId = 1)

                assertEquals(BlockReasonCode.CARRIER_LABEL, onAustralianSim.reasonCode)
                assertFalse(onAmericanSim.reasonCode == BlockReasonCode.CARRIER_LABEL)
            }
        }

    @Test
    fun `a SIM that was locked at boot counts as soon as it's unlocked`() =
        runBlocking {
            // While the SIM was locked the phone took its region from the locale.
            var simCountry = ""
            var reads = 0
            val checker =
                CarrierScamLabelChecker(homeRegionIso = "US") { _, _ ->
                    reads++
                    simCountry
                }
            val text =
                CheckContext(
                    appContext = ApplicationProvider.getApplicationContext(),
                    number = "UNVERIFIED",
                    smsBody = APPOINTMENT,
                    realtimeCall = true,
                    prefs = emptyPreferences(),
                    subscriptionId = 2,
                )

            assertNull("a locked SIM reads empty and the region falls back", checker.check(text))
            simCountry = "au"
            assertEquals("carrier_label", checker.check(text)?.matchSource)
            assertEquals("the empty reading was not kept", 2, reads)
        }

    private companion object {
        const val APPOINTMENT = "Your appointment is on Tuesday at 3 PM"
    }
}
