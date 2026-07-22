package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushAlertRegistryTest {
    @Test
    fun `supported sources have stable human-readable labels`() {
        assertEquals("Uber Driver", PushAlertRegistry.displayNameFor("com.ubercab.driver"))
        assertEquals("Google Calendar", PushAlertRegistry.displayNameFor("com.google.android.calendar"))
        assertEquals("Amazon Shopping", PushAlertRegistry.displayNameFor("com.amazon.mShop.android.shopping"))
        assertEquals("USPS", PushAlertRegistry.displayNameFor("gov.usps.mobile"))
        assertTrue(
            PushAlertRegistry.ALERT_SOURCE_PACKAGES.all {
                PushAlertRegistry.displayNameFor(it).isNotBlank()
            },
        )
    }
}
