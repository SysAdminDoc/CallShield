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
 * The same cases under Robolectric, plus a check that the JDK's NFKC and
 * character types agree with ICU's, which is what a phone runs.
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
}
