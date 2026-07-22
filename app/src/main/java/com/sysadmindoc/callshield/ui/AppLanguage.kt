package com.sysadmindoc.callshield.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R

data class AppLanguageOption(
    val languageTag: String,
    @param:StringRes val labelRes: Int,
)

object AppLanguage {
    const val SYSTEM_DEFAULT_TAG = ""
    const val ENGLISH_TAG = "en"
    const val ACCENTED_PSEUDO_TAG = "en-XA"
    const val RTL_PSEUDO_TAG = "ar-XB"

    fun options(includePseudoLocales: Boolean = BuildConfig.DEBUG): List<AppLanguageOption> =
        buildList {
            add(AppLanguageOption(SYSTEM_DEFAULT_TAG, R.string.settings_language_system_default))
            add(AppLanguageOption(ENGLISH_TAG, R.string.settings_language_english))
            if (includePseudoLocales) {
                add(AppLanguageOption(ACCENTED_PSEUDO_TAG, R.string.settings_language_pseudo_accented))
                add(AppLanguageOption(RTL_PSEUDO_TAG, R.string.settings_language_pseudo_rtl))
            }
        }

    fun currentLanguageTag(): String = normalizeLanguageTag(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(','))

    fun selectLanguage(languageTag: String) {
        val normalized = normalizeLanguageTag(languageTag)
        val locales =
            if (normalized.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(normalized)
            }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    internal fun normalizeLanguageTag(languageTag: String): String =
        when (languageTag.trim()) {
            ENGLISH_TAG -> ENGLISH_TAG
            ACCENTED_PSEUDO_TAG -> ACCENTED_PSEUDO_TAG
            RTL_PSEUDO_TAG -> RTL_PSEUDO_TAG
            else -> SYSTEM_DEFAULT_TAG
        }
}
