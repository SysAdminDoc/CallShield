@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.PortableBackupCrypto
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

internal val backupSectionOrder =
    listOf(
        BackupRestore.BackupSection.BLOCKED_NUMBERS,
        BackupRestore.BackupSection.WHITELIST,
        BackupRestore.BackupSection.WILDCARD_RULES,
        BackupRestore.BackupSection.RANGE_RULES,
        BackupRestore.BackupSection.KEYWORD_RULES,
        BackupRestore.BackupSection.SETTINGS,
        BackupRestore.BackupSection.LOGS,
    )

/**
 * Backup and restore. The screen owns the form state, so switching to Basic
 * and back keeps the chosen sections and any passphrase being typed.
 */
@Composable
internal fun BackupRestoreSettings(
    viewModel: MainViewModel,
    backupSections: Set<BackupRestore.BackupSection>,
    onBackupSectionsChange: (Set<BackupRestore.BackupSection>) -> Unit,
    restoreSections: Set<BackupRestore.BackupSection>,
    onRestoreSectionsChange: (Set<BackupRestore.BackupSection>) -> Unit,
    backupProtection: BackupProtectionForm,
    onBackupProtectionChange: (BackupProtectionForm) -> Unit,
    restorePassphrase: String,
    onRestorePassphraseChange: (String) -> Unit,
) {
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.settings_backup_restore)) {
        val restoreLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    viewModel.restore(
                        it,
                        restoreSections,
                        restorePassphrase.toCharArray().takeIf(CharArray::isNotEmpty),
                    )
                    onRestorePassphraseChange("")
                }
            }
        val restoreResult by viewModel.restoreResult.collectAsStateWithLifecycle()
        val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()
        val restoreUndo by viewModel.restoreUndo.collectAsStateWithLifecycle()

        BackupSectionPicker(
            title = stringResource(R.string.settings_backup_sections_title),
            selectedSections = backupSections,
            onSelectedSectionsChange = onBackupSectionsChange,
        )
        Spacer(Modifier.height(8.dp))
        BackupProtectionControls(
            form = backupProtection,
            onFormChange = onBackupProtectionChange,
        )
        Spacer(Modifier.height(8.dp))
        BackupSectionPicker(
            title = stringResource(R.string.settings_restore_sections_title),
            selectedSections = restoreSections,
            onSelectedSectionsChange = {
                onRestoreSectionsChange(it)
                viewModel.clearRestorePreview()
            },
        )
        Spacer(Modifier.height(8.dp))
        RestorePassphraseField(
            passphrase = restorePassphrase,
            onPassphraseChange = {
                onRestorePassphraseChange(it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH))
                viewModel.clearRestorePreview()
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumActionButton(
                label = stringResource(R.string.settings_backup),
                icon = Icons.Default.Backup,
                color = CatGreen,
                onClick = {
                    hapticTick(context)
                    viewModel.backup(
                        backupSections,
                        backupProtection.passphrase.toCharArray().takeIf { backupProtection.enabled },
                    )
                    onBackupProtectionChange(backupProtection.copy(passphrase = "", confirmation = ""))
                },
                enabled = backupSections.isNotEmpty() && backupProtection.isValid,
                modifier = Modifier.weight(1f),
            )
            PremiumActionButton(
                label = stringResource(R.string.settings_restore),
                icon = Icons.Default.Restore,
                color = CatBlue,
                onClick = {
                    hapticTick(context)
                    restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                },
                enabled = restoreSections.isNotEmpty(),
                modifier = Modifier.weight(1f),
                outlined = true,
            )
        }
        restorePreview?.let { preview ->
            RestorePreviewPanel(
                preview = preview,
                onMerge = {
                    hapticTick(context)
                    viewModel.applyRestore(BackupRestore.RestoreMode.MERGE)
                },
                onReplace = {
                    hapticTick(context)
                    viewModel.applyRestore(BackupRestore.RestoreMode.REPLACE)
                },
                onCancel = {
                    hapticTick(context)
                    viewModel.clearRestorePreview()
                },
            )
        }
        restoreResult?.let { status ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.text,
                    // Announce the restore outcome: it is the only feedback for
                    // a destructive, data-replacing operation, and it used to
                    // appear and disappear silently.
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.success) CatGreen else CatPeach,
                )
                if (restoreUndo != null) {
                    TextButton(
                        onClick = {
                            hapticTick(context)
                            viewModel.undoRestore()
                        },
                    ) {
                        Text(stringResource(R.string.backup_restore_undo), color = CatBlue)
                    }
                }
            }
            LaunchedEffect(status) {
                // Long enough for a screen reader to reach and read it, and
                // longer while a Replace can still be undone.
                kotlinx.coroutines.delay(if (restoreUndo != null) 30_000 else 12_000)
                viewModel.clearRestoreResult()
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.settings_backup_includes), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
        if (
            BackupRestore.BackupSection.LOGS in backupSections ||
            BackupRestore.BackupSection.LOGS in restoreSections
        ) {
            Text(
                stringResource(R.string.settings_backup_logs_privacy),
                style = MaterialTheme.typography.labelSmall,
                color = CatPeach,
            )
        }
    }
}

/**
 * Persists a backup section selection across activity recreation by name.
 * Unknown names are dropped so a downgrade can't crash the restore form.
 */
internal val BackupSectionSetSaver: Saver<Set<BackupRestore.BackupSection>, ArrayList<String>> =
    Saver(
        save = { sections -> ArrayList(sections.map(BackupRestore.BackupSection::name)) },
        restore = { names ->
            names.mapNotNullTo(linkedSetOf()) { name ->
                BackupRestore.BackupSection.entries.firstOrNull { it.name == name }
            }
        },
    )

internal data class BackupProtectionForm(
    val enabled: Boolean = false,
    val passphrase: String = "",
    val confirmation: String = "",
) {
    val isValid: Boolean
        get() =
            !enabled ||
                (
                    passphrase.length in
                        PortableBackupCrypto.MIN_PASSPHRASE_LENGTH..PortableBackupCrypto.MAX_PASSPHRASE_LENGTH &&
                        passphrase == confirmation
                )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun BackupProtectionControls(
    form: BackupProtectionForm,
    onFormChange: (BackupProtectionForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(SETTINGS_BACKUP_ENCRYPTION_TOGGLE_TAG)
                    .padding(vertical = 4.dp)
                    .toggleable(
                        value = form.enabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            onFormChange(if (enabled) form.copy(enabled = true) else BackupProtectionForm())
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = if (form.enabled) CatGreen else CatOverlay)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_backup_encrypt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatText,
                )
                Text(
                    stringResource(R.string.settings_backup_encrypt_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
            }
            Switch(
                checked = form.enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = CatGreen),
            )
        }
        if (form.enabled) {
            BackupPassphraseFields(form, onFormChange)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun BackupPassphraseFields(
    form: BackupProtectionForm,
    onFormChange: (BackupProtectionForm) -> Unit,
) {
    val tooShort = form.passphrase.isNotEmpty() && form.passphrase.length < PortableBackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = form.confirmation.isNotEmpty() && form.confirmation != form.passphrase
    OutlinedTextField(
        value = form.passphrase,
        onValueChange = {
            onFormChange(form.copy(passphrase = it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_BACKUP_PASSPHRASE_TAG),
        label = { Text(stringResource(R.string.settings_backup_passphrase)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.settings_backup_passphrase_hint,
                    PortableBackupCrypto.MIN_PASSPHRASE_LENGTH,
                ),
            )
        },
        leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = tooShort,
    )
    OutlinedTextField(
        value = form.confirmation,
        onValueChange = {
            onFormChange(form.copy(confirmation = it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_BACKUP_CONFIRM_TAG),
        label = { Text(stringResource(R.string.settings_backup_passphrase_confirm)) },
        supportingText = { if (mismatch) Text(stringResource(R.string.settings_backup_passphrase_mismatch)) },
        leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = mismatch,
    )
    Text(
        stringResource(R.string.settings_backup_passphrase_not_saved),
        style = MaterialTheme.typography.labelSmall,
        color = CatPeach,
    )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun RestorePassphraseField(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_RESTORE_PASSPHRASE_TAG),
        label = { Text(stringResource(R.string.settings_restore_passphrase)) },
        supportingText = { Text(stringResource(R.string.settings_restore_passphrase_hint)) },
        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun BackupSectionPicker(
    title: String,
    selectedSections: Set<BackupRestore.BackupSection>,
    onSelectedSectionsChange: (Set<BackupRestore.BackupSection>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
        backupSectionOrder.forEach { section ->
            val selected = section in selectedSections
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked ->
                        onSelectedSectionsChange(
                            if (checked) {
                                selectedSections + section
                            } else {
                                selectedSections - section
                            },
                        )
                    },
                    colors = CheckboxDefaults.colors(checkedColor = CatGreen, uncheckedColor = CatOverlay),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        backupSectionTitle(section),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        backupSectionDescription(section),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (section == BackupRestore.BackupSection.LOGS) CatPeach else CatOverlay,
                    )
                }
            }
        }
    }
}

@Composable
internal fun backupSectionTitle(section: BackupRestore.BackupSection): String =
    when (section) {
        BackupRestore.BackupSection.BLOCKED_NUMBERS -> stringResource(R.string.backup_section_blocked)
        BackupRestore.BackupSection.WHITELIST -> stringResource(R.string.backup_section_whitelist)
        BackupRestore.BackupSection.WILDCARD_RULES -> stringResource(R.string.backup_section_wildcards)
        BackupRestore.BackupSection.RANGE_RULES -> stringResource(R.string.backup_section_ranges)
        BackupRestore.BackupSection.KEYWORD_RULES -> stringResource(R.string.backup_section_keywords)
        BackupRestore.BackupSection.SETTINGS -> stringResource(R.string.backup_section_settings)
        BackupRestore.BackupSection.LOGS -> stringResource(R.string.backup_section_logs)
    }

@Composable
internal fun backupSectionDescription(section: BackupRestore.BackupSection): String =
    when (section) {
        BackupRestore.BackupSection.BLOCKED_NUMBERS -> stringResource(R.string.backup_section_blocked_desc)
        BackupRestore.BackupSection.WHITELIST -> stringResource(R.string.backup_section_whitelist_desc)
        BackupRestore.BackupSection.WILDCARD_RULES -> stringResource(R.string.backup_section_wildcards_desc)
        BackupRestore.BackupSection.RANGE_RULES -> stringResource(R.string.backup_section_ranges_desc)
        BackupRestore.BackupSection.KEYWORD_RULES -> stringResource(R.string.backup_section_keywords_desc)
        BackupRestore.BackupSection.SETTINGS -> stringResource(R.string.backup_section_settings_desc)
        BackupRestore.BackupSection.LOGS -> stringResource(R.string.backup_section_logs_desc)
    }

@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
internal fun RestorePreviewPanel(
    preview: BackupRestore.RestorePreview,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    val counts = preview.counts
    val conflictTotal = preview.conflicts.total

    Spacer(Modifier.height(10.dp))
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SETTINGS_RESTORE_PREVIEW_TAG),
        shape = RoundedCornerShape(12.dp),
        color = CatBlue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PremiumIconTile(icon = Icons.Default.Restore, color = CatBlue)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.backup_restore_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = CatText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.backup_restore_preview_summary,
                            counts.blockedNumbers,
                            counts.whitelistNumbers,
                            counts.wildcardRules,
                            counts.keywordRules,
                            counts.rangeRules,
                            counts.settings,
                            counts.logs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
            if (counts.settings > 0) {
                Text(
                    stringResource(R.string.backup_restore_preview_settings_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
            }
            if (counts.logs > 0) {
                Text(
                    stringResource(R.string.backup_restore_preview_logs_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatPeach,
                )
            }
            Text(
                if (conflictTotal > 0) {
                    pluralStringResource(
                        R.plurals.backup_restore_preview_conflicts,
                        conflictTotal,
                        conflictTotal,
                    )
                } else {
                    stringResource(R.string.backup_restore_preview_no_conflicts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflictTotal > 0) CatPeach else CatGreen,
            )
            Text(
                stringResource(R.string.backup_restore_replace_warning),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_merge),
                    icon = Icons.Default.Restore,
                    color = CatBlue,
                    onClick = onMerge,
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_replace),
                    icon = Icons.Default.DeleteSweep,
                    color = CatPeach,
                    onClick = onReplace,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Close, null, tint = CatOverlay, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.backup_restore_cancel), color = CatOverlay)
            }
        }
    }
}
