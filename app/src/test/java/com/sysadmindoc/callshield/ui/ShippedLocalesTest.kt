package com.sysadmindoc.callshield.ui

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShippedLocalesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun registeredLocales(): List<String> {
        val tags = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlResourceParser.END_DOCUMENT) {
                if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "locale") {
                    tags += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                }
            }
        }
        return tags
    }

    @Test
    fun `the in-app picker offers every locale Android's picker offers`() {
        val offered = AppLanguage.options(includePseudoLocales = false).map { it.languageTag } - AppLanguage.SYSTEM_DEFAULT_TAG

        assertEquals(registeredLocales().sorted(), offered.sorted())
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `Brazilian Portuguese is translated`() {
        assertEquals("Chamada recebida", context.getString(R.string.overlay_header_incoming_call))
        assertEquals(
            "CallShield",
            context.getString(R.string.app_name),
        )
    }

    @Test
    @Config(qualifiers = "pt-rPT")
    fun `Portuguese from Portugal gets the Brazilian text rather than English`() {
        assertEquals("Chamada recebida", context.getString(R.string.overlay_header_incoming_call))
    }

    @Test
    @Config(qualifiers = "es")
    fun `Spanish is translated`() {
        assertEquals("Llamada entrante", context.getString(R.string.overlay_header_incoming_call))
    }

    @Test
    @Config(qualifiers = "es-rMX")
    fun `a regional Spanish phone gets the Spanish text`() {
        assertEquals("Llamada entrante", context.getString(R.string.overlay_header_incoming_call))
        assertNotEquals("Incoming call", context.getString(R.string.overlay_header_incoming_call))
    }

    @Test
    @Config(qualifiers = "es")
    fun `every language keeps its own name in the picker`() {
        assertEquals("简体中文", context.getString(R.string.settings_language_chinese_simplified))
        assertEquals("Português (Brasil)", context.getString(R.string.settings_language_portuguese_brazil))
        assertEquals("Español", context.getString(R.string.settings_language_spanish))
    }
}
