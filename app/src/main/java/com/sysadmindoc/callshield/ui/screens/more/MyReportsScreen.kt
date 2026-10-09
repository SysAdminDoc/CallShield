package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityReportHistory
import com.sysadmindoc.callshield.data.CommunityReportHistory.Delivery
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.friendlySpamTypeLabel
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.util.localizedDateTimeFormat
import kotlinx.coroutines.delay
import java.util.Date

/**
 * My reports: what this phone reported to the community in the last 90 days
 * and whether each report got there. A spam report made by mistake can be
 * answered with a not-spam report, which goes out through the same outbox.
 */
@Composable
internal fun MyReportsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val reports by viewModel.communityReports.collectAsStateWithLifecycle()
    val status by viewModel.contributeResult.collectAsStateWithLifecycle()
    val dateFormat = remember(context) { localizedDateTimeFormat(context, withYear = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.my_reports_intro), style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
        }
        status?.let { message ->
            item {
                Text(message.text, style = MaterialTheme.typography.bodySmall, color = if (message.success) CatGreen else CatRed)
                LaunchedEffect(message) {
                    delay(STATUS_MILLIS)
                    viewModel.clearContributeResult()
                }
            }
        }
        if (reports.isEmpty()) {
            item {
                Text(stringResource(R.string.my_reports_empty), style = MaterialTheme.typography.bodyMedium, color = CatText)
            }
        }
        items(reports, key = { it.id }) { report ->
            MyReportCard(
                report = report,
                date = dateFormat.format(Date(report.reportedAt)),
                canCorrect = CommunityReportHistory.canCorrect(report, reports),
                onCorrect = { viewModel.correctReport(report.number) },
            )
        }
    }
}

@Composable
private fun MyReportCard(
    report: CommunityReportHistory.Entry,
    date: String,
    canCorrect: Boolean,
    onCorrect: () -> Unit,
) {
    val (deliveryLabel, deliveryColor) = deliveryStyle(report.delivery)
    PremiumCard(accentColor = deliveryColor, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PhoneFormatter.formatIsolated(report.number),
                    style = MaterialTheme.typography.titleMedium,
                    color = CatText,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = deliveryLabel, color = deliveryColor)
            }
            Text(
                stringResource(R.string.my_reports_kind_date, reportKindLabel(report.type), date),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            if (canCorrect) {
                TextButton(onClick = onCorrect, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.my_reports_send_not_spam), color = CatGreen)
                }
            }
        }
    }
}

@Composable
private fun deliveryStyle(delivery: Delivery): Pair<String, Color> =
    when (delivery) {
        Delivery.SENT -> stringResource(R.string.my_reports_sent) to CatGreen
        Delivery.QUEUED -> stringResource(R.string.my_reports_queued) to CatYellow
        Delivery.NOT_SENT -> stringResource(R.string.my_reports_not_sent) to CatRed
    }

@Composable
private fun reportKindLabel(type: String): String =
    when (type) {
        CommunityReportHistory.NOT_SPAM -> stringResource(R.string.my_reports_kind_not_spam)
        "sms_spam" -> stringResource(R.string.my_reports_kind_sms_spam)
        else -> friendlySpamTypeLabel(type)
    }

private const val STATUS_MILLIS = 4_000L
