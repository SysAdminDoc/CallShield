package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.RegionRules
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

internal const val REGION_RULES_ENABLED_TAG = "region_rules_enabled"
internal const val REGION_RULES_CODES_TAG = "region_rules_codes"
internal const val CNAP_TRUST_PATTERNS_TAG = "cnap_trust_patterns"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
fun RegionCnapRulesSheet(
    regionBlockEnabled: Boolean,
    allowedRegions: Set<String>,
    cnapTrustPatterns: Set<String>,
    onSave: (enabled: Boolean, regions: Set<String>, namePatterns: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var blockOutsideRegions by remember { mutableStateOf(regionBlockEnabled) }
    var regionText by remember { mutableStateOf(allowedRegions.sorted().joinToString(", ")) }
    var namePatternText by remember { mutableStateOf(cnapTrustPatterns.sorted().joinToString("\n")) }
    val parsedRegions = RegionRules.parseRegionCodes(regionText)
    val parsedPatterns = RegionRules.parseNamePatterns(namePatternText)
    val canSave = !blockOutsideRegions || parsedRegions.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.region_rules_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.region_rules_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            HorizontalDivider()
            SettingsToggle(
                title = stringResource(R.string.region_rules_block_outside),
                subtitle = stringResource(R.string.region_rules_block_outside_desc),
                icon = Icons.Default.Public,
                checked = blockOutsideRegions,
                toggleTag = REGION_RULES_ENABLED_TAG,
                onCheckedChange = { blockOutsideRegions = it },
            )
            OutlinedTextField(
                value = regionText,
                onValueChange = { regionText = it },
                modifier = Modifier.fillMaxWidth().testTag(REGION_RULES_CODES_TAG),
                enabled = blockOutsideRegions,
                label = { Text(stringResource(R.string.region_rules_allowed_regions)) },
                supportingText = {
                    Text(
                        if (blockOutsideRegions && parsedRegions.isEmpty()) {
                            stringResource(R.string.region_rules_regions_required)
                        } else {
                            stringResource(R.string.region_rules_regions_hint)
                        },
                    )
                },
                isError = blockOutsideRegions && parsedRegions.isEmpty(),
                singleLine = true,
            )
            if (parsedRegions.isNotEmpty()) {
                StatusPill(
                    text = stringResource(R.string.region_rules_regions_count, parsedRegions.size),
                    color = CatBlue,
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.cnap_trust_title),
                style = MaterialTheme.typography.titleSmall,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.cnap_trust_desc),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            OutlinedTextField(
                value = namePatternText,
                onValueChange = { namePatternText = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .testTag(CNAP_TRUST_PATTERNS_TAG),
                label = { Text(stringResource(R.string.cnap_trust_patterns)) },
                supportingText = { Text(stringResource(R.string.cnap_trust_patterns_hint)) },
                minLines = 3,
            )
            Text(
                stringResource(R.string.cnap_trust_priority_notice),
                style = MaterialTheme.typography.labelSmall,
                color = CatPeach,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PremiumActionButton(
                    label = stringResource(R.string.dialog_cancel),
                    icon = Icons.Default.Close,
                    color = CatSubtext,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
                PremiumActionButton(
                    label = stringResource(R.string.settings_save),
                    icon = Icons.Default.Check,
                    color = CatGreen,
                    onClick = {
                        onSave(blockOutsideRegions, parsedRegions, parsedPatterns)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                )
            }
        }
    }
}
