package com.sysadmindoc.callshield.data

import android.app.Application
import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory
import android.icu.text.Normalizer2
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.Normalizer

/**
 * On a phone, `java.text.Normalizer` and `java.lang.Character` are backed by
 * ICU. Under Robolectric they're still the JDK's, so running the cases here
 * doesn't exercise ICU. What does is comparing every JDK call the normalizer
 * makes with ICU's answer for each code point both of them know, which is
 * what makes the JVM cases say what a phone does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SmsTextNormalizerIcuTest {
    @Test
    fun `every case folds as expected under Robolectric`() {
        SmsNormalizerCases.assertAll()
    }

    @Test
    fun `NFKC matches ICU on every case`() {
        val icu = Normalizer2.getNFKCInstance()
        SmsNormalizerCases.cases.forEach { case ->
            assertEquals(case.name, icu.normalize(case.input), Normalizer.normalize(case.input, Normalizer.Form.NFKC))
        }
    }

    @Test
    fun `NFKC matches ICU for every code point`() {
        val icu = Normalizer2.getNFKCInstance()
        val disagreements =
            knownToBoth().filter { cp ->
                val text = String(Character.toChars(cp))
                icu.normalize(text) != Normalizer.normalize(text, Normalizer.Form.NFKC)
            }

        assertEquals(emptyList<String>(), disagreements.take(SHOWN).map(::hex))
    }

    @Test
    fun `format characters are the same set in ICU`() {
        val disagreements =
            (0..Character.MAX_CODE_POINT).filter { cp ->
                val jdkType = Character.getType(cp)
                val icuType = UCharacter.getType(cp)
                // Unicode versions differ between the JDK and ICU; only code
                // points both of them know are compared.
                jdkType != Character.UNASSIGNED.toInt() &&
                    icuType != UCharacterCategory.UNASSIGNED.toInt() &&
                    (jdkType == Character.FORMAT.toInt()) != (icuType == UCharacterCategory.FORMAT.toInt())
            }

        assertEquals(emptyList<Int>(), disagreements)
    }

    @Test
    fun `letters, digits and spaces are the same sets in ICU`() {
        val disagreements =
            knownToBoth().filter { cp ->
                Character.isLetter(cp) != UCharacter.isLetter(cp) ||
                    Character.isLetterOrDigit(cp) != UCharacter.isLetterOrDigit(cp) ||
                    Character.isWhitespace(cp) != UCharacter.isWhitespace(cp) ||
                    Character.isSpaceChar(cp) != UCharacter.isSpaceChar(cp)
            }

        assertEquals(emptyList<String>(), disagreements.take(SHOWN).map(::hex))
    }

    // As above, only code points both of them know are compared.
    private fun knownToBoth(): List<Int> =
        (0..Character.MAX_CODE_POINT).filter { cp ->
            Character.getType(cp) != Character.UNASSIGNED.toInt() &&
                UCharacter.getType(cp) != UCharacterCategory.UNASSIGNED.toInt()
        }

    private fun hex(cp: Int): String = "U+%04X".format(cp)

    private companion object {
        const val SHOWN = 20
    }
}
