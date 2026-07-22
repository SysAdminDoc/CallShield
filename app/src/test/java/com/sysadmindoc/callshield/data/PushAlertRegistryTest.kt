package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushAlertRegistryTest {
    @Test
    fun `supported sources have stable human-readable labels`() {
        assertEquals("Uber Driver", pushAlertSourceDisplayName("com.ubercab.driver"))
        assertEquals("Google Calendar", pushAlertSourceDisplayName("com.google.android.calendar"))
        assertEquals("Amazon Shopping", pushAlertSourceDisplayName("com.amazon.mShop.android.shopping"))
        assertEquals("USPS", pushAlertSourceDisplayName("gov.usps.mobile"))
        assertTrue(
            PushAlertRegistry.ALERT_SOURCE_PACKAGES.all {
                pushAlertSourceDisplayName(it).isNotBlank()
            },
        )
    }
}
