package com.sysadmindoc.callshield.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `release options contain only actual shipped locales`() {
        val options = AppLanguage.options(includePseudoLocales = false)

        assertEquals(listOf("", "en"), options.map { it.languageTag })
    }

    @Test
    fun `debug options expose both Android pseudolocales`() {
        val tags = AppLanguage.options(includePseudoLocales = true).map { it.languageTag }

        assertTrue(AppLanguage.ACCENTED_PSEUDO_TAG in tags)
        assertTrue(AppLanguage.RTL_PSEUDO_TAG in tags)
        assertFalse("es" in tags)
    }

    @Test
    fun `unsupported tags fall back to system default`() {
        assertEquals("", AppLanguage.normalizeLanguageTag("es"))
        assertEquals("", AppLanguage.normalizeLanguageTag("  "))
        assertEquals("en", AppLanguage.normalizeLanguageTag(" en "))
    }

    @Test
    fun `language selection round trips through AppCompat`() {
        try {
            AppLanguage.selectLanguage(AppLanguage.ENGLISH_TAG)
            assertEquals(AppLanguage.ENGLISH_TAG, AppLanguage.currentLanguageTag())
        } finally {
            AppLanguage.selectLanguage(AppLanguage.SYSTEM_DEFAULT_TAG)
        }
    }
}
