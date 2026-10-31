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
    const val CHINESE_SIMPLIFIED_TAG = "zh-CN"
    const val PORTUGUESE_BRAZIL_TAG = "pt-BR"
    const val SPANISH_TAG = "es"
    const val ACCENTED_PSEUDO_TAG = "en-XA"
    const val RTL_PSEUDO_TAG = "ar-XB"

    fun options(includePseudoLocales: Boolean = BuildConfig.DEBUG): List<AppLanguageOption> =
        buildList {
            add(AppLanguageOption(SYSTEM_DEFAULT_TAG, R.string.settings_language_system_default))
            add(AppLanguageOption(ENGLISH_TAG, R.string.settings_language_english))
            add(AppLanguageOption(CHINESE_SIMPLIFIED_TAG, R.string.settings_language_chinese_simplified))
            add(AppLanguageOption(PORTUGUESE_BRAZIL_TAG, R.string.settings_language_portuguese_brazil))
            add(AppLanguageOption(SPANISH_TAG, R.string.settings_language_spanish))
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

    internal fun normalizeLanguageTag(languageTag: String): String {
        val tag = languageTag.trim()
        return when {
            tag == ENGLISH_TAG -> ENGLISH_TAG
            tag == CHINESE_SIMPLIFIED_TAG -> CHINESE_SIMPLIFIED_TAG
            tag == PORTUGUESE_BRAZIL_TAG -> PORTUGUESE_BRAZIL_TAG
            // values-es has no region, so Android's own picker can hand back es-MX.
            tag == SPANISH_TAG || tag.startsWith("$SPANISH_TAG-") -> SPANISH_TAG
            tag == ACCENTED_PSEUDO_TAG -> ACCENTED_PSEUDO_TAG
            tag == RTL_PSEUDO_TAG -> RTL_PSEUDO_TAG
            else -> SYSTEM_DEFAULT_TAG
        }
    }
}
