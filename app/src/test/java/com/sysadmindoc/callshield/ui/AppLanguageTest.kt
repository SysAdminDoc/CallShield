package com.sysadmindoc.callshield.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `release options contain only actual shipped locales`() {
        val options = AppLanguage.options(includePseudoLocales = false)

        assertEquals(listOf("", "en", "zh-CN", "pt-BR", "es"), options.map { it.languageTag })
    }

    @Test
    fun `debug options expose both Android pseudolocales`() {
        val tags = AppLanguage.options(includePseudoLocales = true).map { it.languageTag }

        assertTrue(AppLanguage.ACCENTED_PSEUDO_TAG in tags)
        assertTrue(AppLanguage.RTL_PSEUDO_TAG in tags)
        assertFalse("de" in tags)
    }

    @Test
    fun `unsupported tags fall back to system default`() {
        assertEquals("", AppLanguage.normalizeLanguageTag("de"))
        assertEquals("", AppLanguage.normalizeLanguageTag("  "))
        assertEquals("en", AppLanguage.normalizeLanguageTag(" en "))
    }

    @Test
    fun `shipped translations keep their tags`() {
        assertEquals("zh-CN", AppLanguage.normalizeLanguageTag("zh-CN"))
        assertEquals("pt-BR", AppLanguage.normalizeLanguageTag("pt-BR"))
        assertEquals("es", AppLanguage.normalizeLanguageTag(" es "))
    }

    @Test
    fun `Android's picker can hand back a regional Spanish and it still reads as Spanish`() {
        assertEquals("es", AppLanguage.normalizeLanguageTag("es-MX"))
        assertEquals("es", AppLanguage.normalizeLanguageTag("es-419"))
        assertEquals("", AppLanguage.normalizeLanguageTag("est"))
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
