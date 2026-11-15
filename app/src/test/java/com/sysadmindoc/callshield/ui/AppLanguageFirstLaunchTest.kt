package com.sysadmindoc.callshield.ui

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.AppLocalesStorageHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A language picked for CallShield in Android's own per-app language screen, or restored
 * from a backup, before the app is first opened. AppCompat's activity delegate queues a
 * one-time copy of its own saved language into Android on that first launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppLanguageFirstLaunchTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val localeManager: LocaleManager get() = context.getSystemService(LocaleManager::class.java)

    /** The copy AppCompatDelegateImpl.attachBaseContext2 queues, run the same way it runs. */
    private fun firstLaunchAppCompatCopy() {
        AppCompatDelegate::class.java
            .getDeclaredMethod("syncLocalesToFramework", Context::class.java)
            .apply { isAccessible = true }
            .invoke(null, context)
    }

    @Test
    fun `a language picked in Android settings before the first launch survives it`() {
        localeManager.applicationLocales = LocaleList.forLanguageTags("es")

        AppLanguage.keepSystemChosenLanguage(context)
        firstLaunchAppCompatCopy()

        assertEquals("es", localeManager.applicationLocales.toLanguageTags())
    }

    @Test
    fun `a language saved before Android 13 still moves over when Android holds none`() {
        AppLocalesStorageHelper.persistLocales(context, "pt-BR")

        AppLanguage.keepSystemChosenLanguage(context)
        firstLaunchAppCompatCopy()

        assertEquals("pt-BR", localeManager.applicationLocales.toLanguageTags())
    }
}
