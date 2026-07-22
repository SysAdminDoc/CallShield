package com.sysadmindoc.callshield.ui.screens.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.NotificationScreeningCategory
import com.sysadmindoc.callshield.data.NotificationScreeningSource
import com.sysadmindoc.callshield.data.NotificationScreeningSources
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

internal const val NOTIFICATION_SCREENING_SOURCE_TAG_PREFIX = "notification_screening_source:"

private data class ScreenedSourceUi(
    val source: NotificationScreeningSource,
    val label: String,
    val installed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
fun NotificationScreeningSourcesSheet(
    enabledPackages: Set<String>,
    onToggle: (packageName: String, enabled: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sources by remember { mutableStateOf<List<ScreenedSourceUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        val packageManager = context.packageManager
        sources =
            NotificationScreeningSources.catalog.map { source ->
                val label =
                    try {
                        val info = packageManager.getApplicationInfo(source.packageName, 0)
                        packageManager.getApplicationLabel(info).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        source.stableName
                    }
                ScreenedSourceUi(
                    source = source,
                    label = label,
                    installed = runCatching { packageManager.getApplicationInfo(source.packageName, 0) }.isSuccess,
                )
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.notification_screening_sources_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.notification_screening_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            StatusPill(
                text =
                    stringResource(
                        R.string.notification_screening_sources_count,
                        enabledPackages.size,
                        sources.size,
                    ),
                color = CatBlue,
            )
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sources, key = { it.source.packageName }) { item ->
                NotificationScreeningSourceRow(
                    item = item,
                    enabled = item.source.packageName in enabledPackages,
                    onToggle = { onToggle(item.source.packageName, it) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PremiumActionButton(
                label = stringResource(R.string.notification_screening_reset),
                icon = Icons.Default.Refresh,
                color = CatBlue,
                onClick = onReset,
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.notification_screening_done),
                icon = Icons.Default.Check,
                color = CatGreen,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun NotificationScreeningSourceRow(
    item: ScreenedSourceUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = (if (enabled) CatGreen else CatOverlay).copy(alpha = 0.06f),
        border = BorderStroke(1.dp, (if (enabled) CatGreen else CatOverlay).copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatText,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sourceCategoryLabel(item.source.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
                Text(
                    stringResource(
                        if (item.installed) {
                            R.string.notification_screening_installed
                        } else {
                            R.string.notification_screening_not_installed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatOverlay,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("$NOTIFICATION_SCREENING_SOURCE_TAG_PREFIX${item.source.packageName}"),
                colors = SwitchDefaults.colors(checkedTrackColor = CatGreen),
            )
        }
    }
}

@Composable
private fun sourceCategoryLabel(category: NotificationScreeningCategory): String =
    stringResource(
        when (category) {
            NotificationScreeningCategory.RCS -> R.string.notification_screening_category_messages
            NotificationScreeningCategory.PRIVATE_MESSAGE -> R.string.notification_screening_category_private
            NotificationScreeningCategory.EMAIL -> R.string.notification_screening_category_email
        },
    )
