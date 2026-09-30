@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.AppLanguage
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

/** Theme and language. */
@Composable
internal fun AppearanceSettings(viewModel: MainViewModel) {
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    val languageOptions = AppLanguage.options()
    val currentLanguageTag = AppLanguage.currentLanguageTag()
    val currentLanguage =
        languageOptions.firstOrNull { it.languageTag == currentLanguageTag }
            ?: languageOptions.first()
    SettingsCard(stringResource(R.string.settings_appearance)) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_theme),
            value = stringResource(appTheme.labelResource()),
            icon = Icons.Default.Palette,
            modifier = Modifier.testTag(SETTINGS_THEME_ROW_TAG),
            onClick = { showThemeDialog = true },
        )
        GradientDivider()
        SettingsLinkRow(
            title = stringResource(R.string.settings_language),
            value = stringResource(currentLanguage.labelRes),
            icon = Icons.Default.Language,
            onClick = { showLanguageDialog = true },
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.Palette, contentDescription = null, tint = CatGreen) },
            title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppThemeMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == appTheme,
                                        role = Role.RadioButton,
                                        onClick = {
                                            viewModel.setAppTheme(option)
                                            showThemeDialog = false
                                        },
                                    ).testTag("settings_theme_option_${option.storageValue}")
                                    .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == appTheme,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = CatGreen),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(option.labelResource()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.Language, contentDescription = null, tint = CatBlue) },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                Column {
                    languageOptions.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    // selectable (not clickable) so TalkBack
                                    // announces the radio role and which
                                    // language is currently active — matching
                                    // the theme dialog above.
                                    .selectable(
                                        selected = option.languageTag == currentLanguageTag,
                                        role = Role.RadioButton,
                                        onClick = {
                                            showLanguageDialog = false
                                            AppLanguage.selectLanguage(option.languageTag)
                                        },
                                    ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.languageTag == currentLanguageTag,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = CatBlue),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(option.labelRes))
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Text(
                            stringResource(R.string.settings_language_debug_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}

internal fun AppThemeMode.labelResource(): Int =
    when (this) {
        AppThemeMode.System -> R.string.settings_theme_system
        AppThemeMode.Light -> R.string.settings_theme_light
        AppThemeMode.Graphite -> R.string.settings_theme_graphite
        AppThemeMode.Amoled -> R.string.settings_theme_amoled
    }
