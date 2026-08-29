package com.sysadmindoc.callshield.ui.screens.stats

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.LogAggregate
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.friendlyMatchReasonLabel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.delay
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DailyStat(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val count: Int,
)

@Composable
fun StatsScreen(viewModel: MainViewModel) {
    val logCount by viewModel.logCount.collectAsStateWithLifecycle()
    val logCallCount by viewModel.logCallCount.collectAsStateWithLifecycle()
    val logSmsCount by viewModel.logSmsCount.collectAsStateWithLifecycle()
    val totalBlocked by viewModel.totalBlocked.collectAsStateWithLifecycle()
    val spamCount by viewModel.spamCount.collectAsStateWithLifecycle()
    val reasonAggregates by viewModel.logReasonCounts.collectAsStateWithLifecycle()
    val hourAggregates by viewModel.logHourCounts.collectAsStateWithLifecycle()
    val topNumberAggregates by viewModel.logTopNumbers.collectAsStateWithLifecycle()
    val areaCodeAggregates by viewModel.logAreaCodeCounts.collectAsStateWithLifecycle()
    val numberFormatter = remember { NumberFormat.getIntegerInstance() }

    // Room emits compact projections instead of materializing the unbounded
    // log and its potentially large SMS bodies into the composition.
    val typeBreakdown: List<Map.Entry<BlockReasonCode, Int>> =
        remember(reasonAggregates) {
            val counts = mutableMapOf<BlockReasonCode, Int>()
            reasonAggregates.forEach { aggregate ->
                val reasonCode = BlockReasonCode.fromStored(aggregate.key)
                counts[reasonCode] = (counts[reasonCode] ?: 0) + aggregate.count
            }
            counts
                .entries
                .sortedByDescending { it.value }
        }

    val hourCounts: IntArray =
        remember(hourAggregates) {
            IntArray(24).also { hours ->
                hourAggregates.forEach { aggregate ->
                    aggregate.key.toIntOrNull()?.takeIf { it in hours.indices }?.let { hour ->
                        hours[hour] = aggregate.count
                    }
                }
            }
        }

    val topOffenders: List<Pair<String, Int>> =
        remember(topNumberAggregates) { topNumberAggregates.map { it.key to it.count } }

    val areaCodeCounts: List<Pair<String, Int>> =
        remember(areaCodeAggregates) { areaCodeAggregates.map { it.key to it.count } }

    val dayBucket = rememberDayBucket()
    val todayStart = remember(dayBucket) { currentDayStart() }
    val dayQueryStart = dayOffset(todayStart, -14)
    val dailyAggregates by
        remember(dayQueryStart) { viewModel.observeLogDayCounts(dayQueryStart) }
            .collectAsStateWithLifecycle(initialValue = emptyList<LogAggregate>())

    // Weekly activity aligns to true local calendar days while the SQL query
    // remains limited to the two weeks needed for the comparison.
    val dailyStats = remember(dailyAggregates, todayStart) { buildRecentDailyStats(dailyAggregates, todayStart) }
    val dailyCounts = remember(dailyStats) { dailyStats.map { it.label to it.count } }
    val weeklyTotal = remember(dailyStats) { dailyStats.sumOf { it.count } }
    val previousWeekTotal =
        remember(dailyAggregates, dailyStats) {
            previousWeekCount(dailyAggregates, dailyStats.firstOrNull()?.startMillis)
        }
    val weeklyDelta = weeklyTotal - previousWeekTotal
    val busiestDay = remember(dailyStats) { dailyStats.maxByOrNull { it.count } }

    val sourceBreakdown =
        remember(typeBreakdown) { typeBreakdown.take(8).associate { it.key to it.value } }

    val thisMonthStart = remember(todayStart) { monthStart(todayStart, 0) }
    val lastMonthStart = remember(todayStart) { monthStart(todayStart, -1) }
    val thisMonthCount by
        remember(thisMonthStart) { viewModel.observeLogCountBetween(thisMonthStart, Long.MAX_VALUE) }
            .collectAsStateWithLifecycle(0)
    val lastMonthCount by
        remember(lastMonthStart, thisMonthStart) {
            viewModel.observeLogCountBetween(lastMonthStart, thisMonthStart)
        }.collectAsStateWithLifecycle(0)
    val monthlyTrend =
        remember(thisMonthCount, lastMonthCount) { Pair(thisMonthCount, lastMonthCount) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatsOverviewCard(
            weeklyTotal = weeklyTotal,
            weeklyDelta = weeklyDelta,
            topSource = typeBreakdown.firstOrNull()?.key,
            peakHour =
                logCount.takeIf { it > 0 }?.let {
                    val peakHourIndex = hourCounts.indices.maxByOrNull { index -> hourCounts[index] } ?: 0
                    if (hourCounts[peakHourIndex] > 0) formatHourRange(peakHourIndex) else null
                },
        )

        // Summary row
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_calls), numberFormatter.format(logCallCount), CatRed)
            Box(Modifier.width(1.dp).height(58.dp).background(CatMuted))
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_sms), numberFormatter.format(logSmsCount), CatMauve)
            Box(Modifier.width(1.dp).height(58.dp).background(CatMuted))
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_db_size), numberFormatter.format(spamCount), CatGreen)
        }

        if (logCount == 0) {
            PremiumStateCard(
                icon = Icons.Default.BarChart,
                title = stringResource(R.string.stats_no_data),
                body = stringResource(R.string.stats_no_data_desc),
                accentColor = CatBlue,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Weekly Activity bar chart (Canvas)
            PremiumCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    SectionHeader(stringResource(R.string.stats_weekly_activity), CatBlue)
                    Spacer(Modifier.height(8.dp))
                    WeeklyBarChart(dailyCounts = dailyCounts, modifier = Modifier.fillMaxWidth())
                }
            }

            // Source Breakdown donut chart
            if (sourceBreakdown.isNotEmpty()) {
                PremiumCard {
                    Column(modifier = Modifier.padding(14.dp)) {
                        SectionHeader(stringResource(R.string.stats_source_breakdown), CatGreen)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SourceDonutChart(
                                sources = sourceBreakdown,
                                modifier = Modifier,
                            )
                            Spacer(Modifier.width(16.dp))
                            SourceLegend(
                                sources = sourceBreakdown,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Monthly trend
            PremiumCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    SectionHeader(stringResource(R.string.stats_monthly_trend), CatTeal)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.stats_this_month),
                                style = MaterialTheme.typography.labelSmall,
                                color = CatSubtext,
                            )
                            Text(
                                monthlyTrend.first.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = CatText,
                            )
                        }
                        val diff = monthlyTrend.first - monthlyTrend.second
                        val trendColor =
                            when {
                                diff > 0 -> CatRed
                                diff < 0 -> CatGreen
                                else -> CatSubtext
                            }
                        val trendIcon =
                            when {
                                diff > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                                diff < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                                else -> Icons.AutoMirrored.Filled.TrendingFlat
                            }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                trendIcon,
                                contentDescription = null,
                                tint = trendColor,
                                modifier = Modifier.size(28.dp),
                            )
                            val trendText =
                                when {
                                    diff > 0 -> stringResource(R.string.stats_trend_up, diff)
                                    diff < 0 -> stringResource(R.string.stats_trend_down, -diff)
                                    else -> stringResource(R.string.stats_trend_same)
                                }
                            Text(
                                trendText,
                                style = MaterialTheme.typography.labelSmall,
                                color = trendColor,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                stringResource(R.string.stats_last_month),
                                style = MaterialTheme.typography.labelSmall,
                                color = CatSubtext,
                            )
                            Text(
                                monthlyTrend.second.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = CatOverlay,
                            )
                        }
                    }
                }
            }

            PremiumCard(accentColor = CatBlue) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.stats_weekly_highlights), CatBlue)

                    StatsInsightRow(
                        title = stringResource(R.string.stats_highlight_weekly_change),
                        value =
                            when {
                                weeklyDelta > 0 -> stringResource(R.string.stats_change_up, weeklyDelta)
                                weeklyDelta < 0 -> stringResource(R.string.stats_change_down, -weeklyDelta)
                                else -> stringResource(R.string.stats_change_same)
                            },
                        color =
                            when {
                                weeklyDelta > 0 -> CatRed
                                weeklyDelta < 0 -> CatGreen
                                else -> CatOverlay
                            },
                    )

                    StatsInsightRow(
                        title = stringResource(R.string.stats_highlight_busiest_day),
                        value =
                            busiestDay?.takeIf { it.count > 0 }?.let {
                                stringResource(R.string.stats_highlight_busiest_value, it.label, it.count)
                            } ?: stringResource(R.string.stats_insight_waiting),
                        color = CatBlue,
                    )

                    val peakHourEntry =
                        hourCounts.indices
                            .map { it to hourCounts[it] }
                            .maxByOrNull { it.second }
                    StatsInsightRow(
                        title = stringResource(R.string.stats_highlight_peak_window),
                        value =
                            peakHourEntry?.let {
                                stringResource(
                                    R.string.stats_highlight_peak_value,
                                    formatHourRange(it.first),
                                    it.second,
                                )
                            } ?: stringResource(R.string.stats_insight_waiting),
                        color = CatMauve,
                    )
                }
            }

            // Type breakdown
            if (typeBreakdown.isNotEmpty()) {
                PremiumCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(stringResource(R.string.stats_by_detection_method), CatGreen)
                        Spacer(Modifier.height(8.dp))
                        typeBreakdown.take(8).forEach { (reasonCode, count) ->
                            val fraction = count.toFloat() / totalBlocked.coerceAtLeast(1)
                            val color = reasonCodeColor(reasonCode)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(friendlyMatchReasonLabel(reasonCode.wireValue), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(numberFormatter.format(count), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = color,
                                trackColor = CatMuted.copy(alpha = 0.2f),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Top offenders
            if (topOffenders.isNotEmpty()) {
                PremiumCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(stringResource(R.string.stats_top_offenders), CatRed)
                        Spacer(Modifier.height(8.dp))
                        topOffenders.forEachIndexed { i, (number, count) ->
                            val displayNumber =
                                number.takeIf { it.isNotBlank() }?.let(PhoneFormatter::formatIsolated)
                                    ?: stringResource(R.string.stats_unknown_caller)
                            val location =
                                number.takeIf { it.isNotBlank() }?.let(AreaCodeLookup::lookup)
                                    ?: stringResource(R.string.stats_unknown_origin)

                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.", color = CatOverlay, modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(displayNumber, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(location, color = CatSubtext, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(stringResource(R.string.stats_repeat_hits, count), color = CatRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Area code heatmap
            if (areaCodeCounts.isNotEmpty()) {
                PremiumCard(accentColor = CatPeach) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(stringResource(R.string.stats_spam_by_area_code), CatPeach)
                        Spacer(Modifier.height(8.dp))
                        val maxAc = areaCodeCounts.first().second.coerceAtLeast(1)
                        areaCodeCounts.forEach { (ac, count) ->
                            val loc = AreaCodeLookup.lookup("+1$ac") ?: ac
                            val fraction = count.toFloat() / maxAc
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(ac, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp), color = CatPeach)
                                Text(loc, style = MaterialTheme.typography.labelSmall, color = CatSubtext, modifier = Modifier.width(120.dp))
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(3.dp)),
                                    color = CatPeach,
                                    trackColor = CatMuted.copy(alpha = 0.2f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("$count", style = MaterialTheme.typography.labelSmall, color = CatPeach, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Time-of-day heatmap
            if (logCount >= 5) {
                val maxHour = hourCounts.max().coerceAtLeast(1)
                PremiumCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(stringResource(R.string.stats_spam_by_hour), CatMauve)
                        Text(stringResource(R.string.stats_spam_concentrate), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                            hourCounts.forEachIndexed { hour, count ->
                                val fraction = count.toFloat() / maxHour
                                val barColor =
                                    when {
                                        fraction > 0.7f -> CatRed
                                        fraction > 0.4f -> CatPeach
                                        fraction > 0f -> CatBlue.copy(alpha = 0.5f)
                                        else -> CatOverlay.copy(alpha = 0.1f)
                                    }
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                            .background(barColor),
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.stats_time_12a), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                            Text(stringResource(R.string.stats_time_6a), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                            Text(stringResource(R.string.stats_time_12p), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                            Text(stringResource(R.string.stats_time_6p), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                            Text(stringResource(R.string.stats_time_12a), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsOverviewCard(
    weeklyTotal: Int,
    weeklyDelta: Int,
    topSource: BlockReasonCode?,
    peakHour: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.stats_overview_title), CatGreen)
        Text(
            pluralStringResource(R.plurals.stats_threats_stopped, weeklyTotal, weeklyTotal),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = CatText,
        )
        Text(
            when {
                weeklyDelta > 0 -> stringResource(R.string.stats_previous_week_more, weeklyDelta)
                weeklyDelta < 0 -> stringResource(R.string.stats_previous_week_fewer, -weeklyDelta)
                else -> stringResource(R.string.stats_previous_week_same)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = CatSubtext,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsInsightTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_overview_week),
                value = weeklyTotal.toString(),
                color = CatGreen,
            )
            StatsInsightTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_overview_change),
                value =
                    when {
                        weeklyDelta > 0 -> stringResource(R.string.stats_change_up, weeklyDelta)
                        weeklyDelta < 0 -> stringResource(R.string.stats_change_down, -weeklyDelta)
                        else -> stringResource(R.string.stats_change_same)
                    },
                color =
                    when {
                        weeklyDelta > 0 -> CatRed
                        weeklyDelta < 0 -> CatGreen
                        else -> CatOverlay
                    },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsInsightTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_overview_top_source),
                value =
                    topSource?.let { friendlyMatchReasonLabel(it.wireValue) }
                        ?: stringResource(R.string.stats_overview_no_source),
                color = CatGreen,
            )
            StatsInsightTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_overview_peak_hour),
                value = peakHour ?: stringResource(R.string.stats_overview_no_peak),
                color = CatMauve,
            )
        }
    }
}

@Composable
private fun StatsInsightTile(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color,
) {
    LedgerCard(modifier = modifier.heightIn(min = 76.dp)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun StatsInsightRow(
    title: String,
    value: String,
    color: Color,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CatText,
            )
        }
    }
}

@Composable
private fun rememberDayBucket(): Int {
    var dayBucket by remember { mutableIntStateOf(currentDayBucket()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay())
            dayBucket = currentDayBucket()
        }
    }

    return dayBucket
}

private fun currentDayBucket(): Int {
    val calendar = Calendar.getInstance()
    return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun currentDayStart(): Long =
    Calendar
        .getInstance()
        .apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

private fun dayOffset(
    start: Long,
    days: Int,
): Long =
    Calendar
        .getInstance()
        .apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

private fun millisUntilNextDay(): Long {
    val now = Calendar.getInstance()
    val nextMidnight =
        Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    return (nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
}

private fun buildRecentDailyStats(
    dailyAggregates: List<LogAggregate>,
    todayStart: Long,
): List<DailyStat> {
    val countsByDay = dailyAggregates.associate { it.key to it.count }
    val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    return (6 downTo 0).map { daysAgo ->
        val dayStart =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = todayStart
                    add(Calendar.DAY_OF_YEAR, -daysAgo)
                }.timeInMillis
        val dayEnd =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = dayStart
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
        val label =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = dayStart
                }.let { calendar ->
                    DateFormatSymbols
                        .getInstance()
                        .shortWeekdays[calendar.get(Calendar.DAY_OF_WEEK)]
                        .ifBlank { calendar.get(Calendar.DAY_OF_WEEK).toString() }
                }

        DailyStat(
            label = label,
            startMillis = dayStart,
            endMillis = dayEnd,
            count = countsByDay[dayKeyFormat.format(Date(dayStart))] ?: 0,
        )
    }
}

private fun previousWeekCount(
    dailyAggregates: List<LogAggregate>,
    currentWeekStart: Long?,
): Int {
    currentWeekStart ?: return 0
    val countsByDay = dailyAggregates.associate { it.key to it.count }
    val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val previousWeekStart =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = currentWeekStart
                add(Calendar.DAY_OF_YEAR, -7)
            }.timeInMillis

    var count = 0
    var dayStart = previousWeekStart
    while (dayStart < currentWeekStart) {
        count += countsByDay[dayKeyFormat.format(Date(dayStart))] ?: 0
        dayStart =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = dayStart
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
    }
    return count
}

private fun monthStart(
    dayBucket: Long,
    monthOffset: Int,
): Long =
    Calendar
        .getInstance()
        .apply {
            timeInMillis = dayBucket
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

// friendlyMatchReasonLabel moved to ui/MatchReasonLabels.kt so every screen
// that shows a block reason shares the same localized labels.

@Composable
private fun formatHourRange(hour: Int): String {
    // Honor the device's 12/24-hour preference and locale — hardcoded
    // "2 PM-3 PM" is wrong for every 24-hour user. Reuses the Settings
    // screen's formatter so both surfaces render hours identically.
    val use24Hour =
        android.text.format.DateFormat
            .is24HourFormat(LocalContext.current)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return "${com.sysadmindoc.callshield.ui.screens.settings.formatHourLabel(hour % 24, use24Hour, locale)}–" +
        com.sysadmindoc.callshield.ui.screens.settings
            .formatHourLabel((hour + 1) % 24, use24Hour, locale)
}

// ─── Weekly Bar Chart (Canvas) ──────────────────────────────────────
@Composable
fun WeeklyBarChart(
    dailyCounts: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    val maxCount = dailyCounts.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val barColor = CatGreen

    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        val barWidth = size.width / (dailyCounts.size * 2f)
        val gap = barWidth

        dailyCounts.forEachIndexed { index, (_, count) ->
            val barHeight = (count.toFloat() / maxCount) * (size.height - 30.dp.toPx())
            val x = index * (barWidth + gap) + gap / 2

            // Bar with rounded top
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - 30.dp.toPx() - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
    // Day labels below
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        dailyCounts.forEach { (day, count) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
            }
        }
    }
}

// ─── Source Donut Chart (Canvas) ────────────────────────────────────
@Composable
fun SourceDonutChart(
    sources: Map<BlockReasonCode, Int>,
    modifier: Modifier = Modifier,
) {
    val total = sources.values.sum().coerceAtLeast(1)
    val colors = listOf(CatGreen, CatBlue, CatMauve, CatPeach, CatRed, CatYellow, CatTeal, CatLavender)

    Box(modifier = modifier.size(160.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            var startAngle = -90f
            sources.entries.forEachIndexed { index, (_, count) ->
                val sweep = (count.toFloat() / total) * 360f
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Butt),
                    topLeft = Offset(12.dp.toPx(), 12.dp.toPx()),
                    size = Size(size.width - 24.dp.toPx(), size.height - 24.dp.toPx()),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                total.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CatText,
            )
            Text(
                stringResource(R.string.stats_donut_total),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext,
            )
        }
    }
}

// ─── Source Legend ──────────────────────────────────────────────────
@Composable
fun SourceLegend(
    sources: Map<BlockReasonCode, Int>,
    modifier: Modifier = Modifier,
) {
    val total = sources.values.sum().coerceAtLeast(1)
    val colors = listOf(CatGreen, CatBlue, CatMauve, CatPeach, CatRed, CatYellow, CatTeal, CatLavender)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.entries.forEachIndexed { index, (source, count) ->
            val pct = (count * 100f / total).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .background(colors[index % colors.size], CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    friendlyMatchReasonLabel(source.wireValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.stats_source_count_percent, count, pct),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors[index % colors.size],
                )
            }
        }
    }
}

@Composable
private fun reasonCodeColor(reasonCode: BlockReasonCode): Color =
    when (reasonCode) {
        BlockReasonCode.DATABASE, BlockReasonCode.DB_PREFIX_EXPANSION, BlockReasonCode.HOT_LIST -> CatGreen
        BlockReasonCode.HEURISTIC, BlockReasonCode.CAMPAIGN_BURST -> CatBlue
        BlockReasonCode.SMS_CONTENT, BlockReasonCode.SPAM_DOMAIN, BlockReasonCode.KEYWORD -> CatMauve
        BlockReasonCode.ML_SCORER -> CatTeal
        BlockReasonCode.RCS_FILTER -> CatLavender
        BlockReasonCode.STIR_SHAKEN_FAILED, BlockReasonCode.STIR_SHAKEN_TRUSTED, BlockReasonCode.WILDCARD -> CatYellow
        BlockReasonCode.PREFIX, BlockReasonCode.REGION_BLOCK, BlockReasonCode.FREQUENCY -> CatPeach
        BlockReasonCode.USER_BLOCKLIST, BlockReasonCode.TEMPORARY_BLOCK -> CatRed
        BlockReasonCode.TIME_BLOCK -> CatMauve
        else -> CatSubtext
    }

@Composable
fun MiniStat(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color,
) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = color)
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = CatSubtext,
        )
    }
}
