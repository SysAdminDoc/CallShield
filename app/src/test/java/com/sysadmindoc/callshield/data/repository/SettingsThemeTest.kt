package com.sysadmindoc.callshield.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsThemeTest {
    @Test
    fun `theme preference rejects unknown values`() {
        assertEquals("light", sanitizeAppTheme(null))
        assertEquals("light", sanitizeAppTheme("neon"))
        assertEquals("system", sanitizeAppTheme("system"))
        assertEquals("light", sanitizeAppTheme("light"))
        assertEquals("graphite", sanitizeAppTheme("graphite"))
        assertEquals("amoled", sanitizeAppTheme("amoled"))
    }
}
