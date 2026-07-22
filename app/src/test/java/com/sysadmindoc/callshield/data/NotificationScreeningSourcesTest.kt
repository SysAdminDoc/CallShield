package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationScreeningSourcesTest {
    @Test
    fun `only Google and Samsung Messages are enabled by default`() {
        assertEquals(
            setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            NotificationScreeningSources.enabledPackages(null),
        )
    }

    @Test
    fun `stored opt-ins are filtered to supported packages`() {
        val enabled =
            NotificationScreeningSources.enabledPackages(
                setOf("com.whatsapp", "untrusted.example"),
            )

        assertEquals(setOf("com.whatsapp"), enabled)
    }

    @Test
    fun `unselected source is rejected before notification content is read`() {
        val enabled = setOf("com.google.android.apps.messaging")

        assertTrue(NotificationScreeningSources.shouldReadPackage("com.google.android.apps.messaging", enabled))
        assertFalse(NotificationScreeningSources.shouldReadPackage("com.whatsapp", enabled))
        assertFalse(NotificationScreeningSources.shouldReadPackage("unknown.package", enabled))
    }

    @Test
    fun `catalog package names are unique`() {
        assertEquals(
            NotificationScreeningSources.catalog.size,
            NotificationScreeningSources.catalog
                .map { it.packageName }
                .toSet()
                .size,
        )
    }
}
