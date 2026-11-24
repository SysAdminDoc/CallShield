package com.sysadmindoc.callshield.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryWaitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun message(
        seconds: Int,
        locale: Locale = Locale.ENGLISH,
    ): String {
        val localized =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(locale) },
            )
        return rateLimitedMessage(localized.resources, seconds)
    }

    @Test
    fun `a wait under a minute stays in seconds`() {
        assertEquals("Too many reports. Wait 1 second and try again.", message(1))
        assertEquals("Too many reports. Wait 59 seconds and try again.", message(59))
        // A Retry-After of 0 still asks for a wait, never "Wait 0 seconds".
        assertEquals("Too many reports. Wait 1 second and try again.", message(0))
    }

    @Test
    fun `a wait up to an hour and a half reads in minutes, rounded up`() {
        assertEquals("Too many reports. Wait 1 minute and try again.", message(60))
        assertEquals("Too many reports. Wait 2 minutes and try again.", message(61))
        // What the report service sent on 2026-11-24 for a repeat report.
        assertEquals("Too many reports. Wait 68 minutes and try again.", message(4039))
        assertEquals("Too many reports. Wait 90 minutes and try again.", message(90 * 60))
    }

    @Test
    fun `a longer wait reads in hours, rounded up`() {
        assertEquals("Too many reports. Wait 2 hours and try again.", message(90 * 60 + 1))
        assertEquals("Too many reports. Wait 14 hours and try again.", message(14 * 3600 - 6 * 60))
        assertEquals("Too many reports. Wait 24 hours and try again.", message(24 * 3600))
        assertEquals(RoundedWait(WaitUnit.MINUTES, 60), roundedWait(3600))
        assertEquals(RoundedWait(WaitUnit.HOURS, 2), roundedWait(5401))
    }

    @Test
    fun `every shipped language has the minute and hour forms`() {
        assertEquals("Demasiados reportes. Espera 68 minutos e inténtalo de nuevo.", message(4039, Locale.forLanguageTag("es")))
        assertEquals("Denúncias demais. Aguarde 2 horas e tente novamente.", message(5401, Locale.forLanguageTag("pt-BR")))
        assertEquals("举报过于频繁。请等待 14 小时后重试。", message(14 * 3600 - 6 * 60, Locale.forLanguageTag("zh-CN")))
    }
}
