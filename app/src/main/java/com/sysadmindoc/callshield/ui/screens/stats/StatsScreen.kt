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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Calendar

private data class DailyStat(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val count: Int
)

@Composable
fun StatsScreen(viewModel: MainViewModel) {
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    val totalBlocked by viewModel.totalBlocked.collectAsState()
    val spamCount by viewModel.spamCount.collectAsState()
    val numberFormatter = remember { NumberFormat.getIntegerInstance() }

    val callsOnly = blockedCalls.filter { it.isCall }
    val smsOnly = blockedCalls.filter { !it.isCall }

    // Type breakdown
    val typeBreakdown = blockedCalls.groupBy { it.matchReason.ifEmpty { "unknown" } }
        .mapValues { it.value.size }
        .entries.sortedByDescending { it.value }

    // Top offenders
    val topOffenders = blockedCalls.groupBy { it.number }
        .mapValues { it.value.size }
        .entries.sortedByDescending { it.value }
        .take(10)

    val dayBucket = rememberDayBucket()

    // Weekly activity should align to true local calendar days rather than
    // rolling 24-hour windows. This keeps charts stable across the day and
    // fixes the "today" bucket after midnight.
    val dailyStats = remember(blockedCalls, dayBucket) { buildRecentDailyStats(blockedCalls) }
    val dailyCounts = remember(dailyStats) { dailyStats.map { it.label to it.count } }
    val weeklyData = remember(dailyStats) { dailyStats.map { it.count } }
    val maxWeekly = weeklyData.maxOrNull()?.coerceAtLeast(1) ?: 1
    val weeklyTotal = remember(dailyStats) { dailyStats.sumOf { it.count } }
    val previousWeekTotal = remember(blockedCalls, dailyStats) {
        previousWeekCount(blockedCalls, dailyStats.firstOrNull()?.startMillis)
    }
    val weeklyDelta = weeklyTotal - previousWeekTotal
    val busiestDay = remember(dailyStats) { dailyStats.maxByOrNull { it.count } }

    // Source breakdown for donut chart
    val sourceBreakdown = remember(blockedCalls) {
        blockedCalls.groupBy { it.matchReason.ifEmpty { "unknown" } }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(8)
            .associate { it.key to it.value }
    }

    // Monthly trend
    val monthlyTrend = remember(blockedCalls, dayBucket) {
        val thisMonthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val lastMonthStart = Calendar.getInstance().apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val thisMonth = blockedCalls.count { it.timestamp >= thisMonthStart }
        val lastMonth = blockedCalls.count { it.timestamp in lastMonthStart until thisMonthStart }
        Pair(thisMonth, lastMonth)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        StatsOverviewCard(
            weeklyTotal = weeklyTotal,
            weeklyDelta = weeklyDelta,
            topSource = typeBreakdown.firstOrNull()?.let { friendlyMatchReason(it.key) },
            peakHour = blockedCalls.takeIf { it.isNotEmpty() }?.let {
                val hourCounts = IntArray(24).also { hours ->
                    blockedCalls.forEach { call ->
                        val hour = Calendar.getInstance().apply { timeInMillis = call.timestamp }.get(Calendar.HOUR_OF_DAY)
                        hours[hour]++
                    }
                }
                val peakHourIndex = hourCounts.indices.maxByOrNull { index -> hourCounts[index] } ?: 0
                if (hourCounts[peakHourIndex] > 0) formatHourRange(peakHourIndex) else null
            }
        )

        // Summary row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_calls), numberFormatter.format(callsOnly.size), CatRed)
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_sms), numberFormatter.format(smsOnly.size), CatMauve)
            MiniStat(Modifier.weight(1f), stringResource(R.string.stats_db_size), numberFormatter.format(spamCount), CatGreen)
        }

        // Weekly Activity bar chart (Canvas)
        PremiumCard {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(stringResource(R.string.stats_weekly_activity), CatBlue)
                Spacer(Modifier.height(12.dp))
                WeeklyBarChart(dailyCounts = dailyCounts, modifier = Modifier.fillMaxWidth())
            }
        }

        // Source Breakdown donut chart
        if (sourceBreakdown.isNotEmpty()) {
            PremiumCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionHeader(stringResource(R.string.stats_source_breakdown), CatGreen)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SourceDonutChart(
                            sources = sourceBreakdown,
                            modifier = Modifier
                        )
                        Spacer(Modifier.width(16.dp))
                        SourceLegend(
                            sources = sourceBreakdown,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Monthly trend
        PremiumCard {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(stringResource(R.string.stats_monthly_trend), CatTeal)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            stringResource(R.string.stats_this_month),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext
                        )
                        Text(
                            monthlyTrend.first.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = CatText
                        )
                    }
                    val diff = monthlyTrend.first - monthlyTrend.second
                    val trendColor = when {
                        diff > 0 -> CatRed
                        diff < 0 -> CatGreen
                        else -> CatSubtext
                    }
                    val trendIcon = when {
                        diff > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                        diff < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                        else -> Icons.AutoMirrored.Filled.TrendingFlat
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            trendIcon,
                            contentDescription = null,
                            tint = trendColor,
                            modifier = Modifier.size(28.dp)
                        )
                        val trendText = when {
                            diff > 0 -> stringResource(R.string.stats_trend_up, diff)
                            diff < 0 -> stringResource(R.string.stats_trend_down, -diff)
                            else -> stringResource(R.string.stats_trend_same)
                        }
                        Text(
                            trendText,
                            style = MaterialTheme.typography.labelSmall,
                            color = trendColor
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.stats_last_month),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext
                        )
                        Text(
                            monthlyTrend.second.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = CatOverlay
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
                    value = when {
                        weeklyDelta > 0 -> stringResource(R.string.stats_change_up, weeklyDelta)
                        weeklyDelta < 0 -> stringResource(R.string.stats_change_down, -weeklyDelta)
                        else -> stringResource(R.string.stats_change_same)
                    },
                    color = when {
                        weeklyDelta > 0 -> CatRed
                        weeklyDelta < 0 -> CatGreen
                        else -> CatOverlay
                    }
                )

                StatsInsightRow(
                    title = stringResource(R.string.stats_highlight_busiest_day),
                    value = busiestDay?.takeIf { it.count > 0 }?.let {
                        stringResource(R.string.stats_highlight_busiest_value, it.label, it.count)
                    } ?: stringResource(R.string.stats_insight_waiting),
                    color = CatBlue
                )

                val peakHourCount = blockedCalls.groupingBy {
                    Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)
                }.eachCount()
                val peakHourEntry = peakHourCount.maxByOrNull { it.value }
                StatsInsightRow(
                    title = stringResource(R.string.stats_highlight_peak_window),
                    value = peakHourEntry?.let {
                        stringResource(
                            R.string.stats_highlight_peak_value,
                            formatHourRange(it.key),
                            it.value
                        )
                    } ?: stringResource(R.string.stats_insight_waiting),
                    color = CatMauve
                )
            }
        }

        // Type breakdown
        if (typeBreakdown.isNotEmpty()) {
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.stats_by_detection_method), CatGreen)
                    Spacer(Modifier.height(8.dp))
                    typeBreakdown.take(8).forEach { (type, count) ->
                        val fraction = count.toFloat() / totalBlocked.coerceAtLeast(1)
                        val color = when {
                            "database" in type || "hot_list" in type -> CatGreen
                            "heuristic" in type || "hot_campaign" in type -> CatBlue
                            "sms_content" in type || "spam_domain" in type -> CatMauve
                            "ml_scorer" in type -> CatTeal
                            "rcs_" in type -> CatLavender
                            "stir" in type -> CatYellow
                            "prefix" in type -> CatPeach
                            "user" in type -> CatRed
                            "wildcard" in type -> CatYellow
                            "time" in type -> CatMauve
                            "frequency" in type -> CatPeach
                            "keyword" in type -> CatMauve
                            else -> CatSubtext
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(friendlyMatchReason(type), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(numberFormatter.format(count), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = color, trackColor = CatMuted.copy(alpha = 0.2f)
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
                        val displayNumber = number.takeIf { it.isNotBlank() }?.let(PhoneFormatter::format)
                            ?: stringResource(R.string.stats_unknown_caller)
                        val location = number.takeIf { it.isNotBlank() }?.let(AreaCodeLookup::lookup)
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
        val areaCodeCounts = remember(blockedCalls) {
            blockedCalls.mapNotNull { AreaCodeLookup.getAreaCode(it.number) }
                .groupBy { it }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
                .take(15)
        }
        if (areaCodeCounts.isNotEmpty()) {
            PremiumCard(accentColor = CatPeach) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.stats_spam_by_area_code), CatPeach)
                    Spacer(Modifier.height(8.dp))
                    val maxAc = areaCodeCounts.first().value.coerceAtLeast(1)
                    areaCodeCounts.forEach { (ac, count) ->
                        val loc = AreaCodeLookup.lookup("+1$ac") ?: ac
                        val fraction = count.toFloat() / maxAc
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ac, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp), color = CatPeach)
                            Text(loc, style = MaterialTheme.typography.labelSmall, color = CatSubtext, modifier = Modifier.width(120.dp))
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = CatPeach, trackColor = CatMuted.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("$count", style = MaterialTheme.typography.labelSmall, color = CatPeach, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Time-of-day heatmap
        if (blockedCalls.size >= 5) {
            val hourCounts = remember(blockedCalls) {
                IntArray(24).also { hours ->
                    blockedCalls.forEach { call ->
                        val hour = Calendar.getInstance().apply { timeInMillis = call.timestamp }.get(Calendar.HOUR_OF_DAY)
                        hours[hour]++
                    }
                }
            }
            val maxHour = hourCounts.max().coerceAtLeast(1)
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.stats_spam_by_hour), CatMauve)
                    Text(stringResource(R.string.stats_spam_concentrate), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                        hourCounts.forEachIndexed { hour, count ->
                            val fraction = count.toFloat() / maxHour
                            val barColor = when {
                                fraction > 0.7f -> CatRed
                                fraction > 0.4f -> CatPeach
                                fraction > 0f -> CatBlue.copy(alpha = 0.5f)
                                else -> CatOverlay.copy(alpha = 0.1f)
                            }
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(fraction.coerceAtLeast(0.02f)).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(barColor)
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

        if (blockedCalls.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.cd_bar_chart), tint = CatOverlay, modifier = Modifier.size(64.dp).accentGlow(CatOverlay, 150f, 0.04f))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.stats_no_data), color = CatSubtext)
                    Text(stringResource(R.string.stats_no_data_desc), color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatsOverviewCard(
    weeklyTotal: Int,
    weeklyDelta: Int,
    topSource: String?,
    peakHour: String?
) {
    PremiumCard(accentColor = if (weeklyTotal > 0) CatGreen else CatOverlay) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.stats_overview_title), CatGreen)
            Text(
                stringResource(R.string.stats_overview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatsInsightTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_overview_week),
                    value = weeklyTotal.toString(),
                    color = CatBlue
                )
                StatsInsightTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_overview_change),
                    value = when {
                        weeklyDelta > 0 -> stringResource(R.string.stats_change_up, weeklyDelta)
                        weeklyDelta < 0 -> stringResource(R.string.stats_change_down, -weeklyDelta)
                        else -> stringResource(R.string.stats_change_same)
                    },
                    color = when {
                        weeklyDelta > 0 -> CatRed
                        weeklyDelta < 0 -> CatGreen
                        else -> CatOverlay
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatsInsightTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_overview_top_source),
                    value = topSource ?: stringResource(R.string.stats_overview_no_source),
                    color = CatGreen
                )
                StatsInsightTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_overview_peak_hour),
                    value = peakHour ?: stringResource(R.string.stats_overview_no_peak),
                    color = CatMauve
                )
            }
        }
    }
}

@Composable
private fun StatsInsightTile(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun StatsInsightRow(
    title: String,
    value: String,
    color: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CatText
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

private fun millisUntilNextDay(): Long {
    val now = Calendar.getInstance()
    val nextMidnight = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
}

private fun buildRecentDailyStats(blockedCalls: List<BlockedCall>): List<DailyStat> {
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return (6 downTo 0).map { daysAgo ->
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }.timeInMillis
        val dayEnd = Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val label = Calendar.getInstance().apply {
            timeInMillis = dayStart
        }.let { calendar ->
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        }

        DailyStat(
            label = label,
            startMillis = dayStart,
            endMillis = dayEnd,
            count = blockedCalls.count { it.timestamp in dayStart until dayEnd }
        )
    }
}

private fun previousWeekCount(blockedCalls: List<BlockedCall>, currentWeekStart: Long?): Int {
    currentWeekStart ?: return 0

    val previousWeekStart = Calendar.getInstance().apply {
        timeInMillis = currentWeekStart
        add(Calendar.DAY_OF_YEAR, -7)
    }.timeInMillis

    return blockedCalls.count { it.timestamp in previousWeekStart until currentWeekStart }
}

private fun friendlyMatchReason(reason: String): String = when {
    reason.contains("database", ignoreCase = true) -> "Spam database"
    reason.contains("hot_list", ignoreCase = true) -> "Hot-list range"
    reason.contains("hot_campaign", ignoreCase = true) -> "Live campaign range"
    reason.contains("heuristic", ignoreCase = true) -> "Heuristic analysis"
    reason.contains("sms_content", ignoreCase = true) -> "SMS content"
    reason.contains("spam_domain", ignoreCase = true) -> "Spam domain"
    reason.contains("ml_scorer", ignoreCase = true) -> "ML scorer"
    reason.contains("rcs_", ignoreCase = true) -> "RCS filter"
    reason.contains("stir", ignoreCase = true) -> "STIR/SHAKEN"
    reason.contains("prefix", ignoreCase = true) -> "Premium prefix"
    reason.contains("wildcard", ignoreCase = true) -> "Wildcard rule"
    reason.contains("keyword", ignoreCase = true) -> "Keyword rule"
    reason.contains("frequency", ignoreCase = true) -> "Repeat caller rule"
    reason.contains("time", ignoreCase = true) -> "Quiet hours"
    reason.contains("user", ignoreCase = true) -> "Manual block"
    reason.isBlank() || reason == "unknown" -> "Unknown"
    else -> reason.replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun formatHourRange(hour: Int): String {
    fun formatSingleHour(value: Int): String {
        val normalized = value % 24
        val suffix = if (normalized < 12) "AM" else "PM"
        val displayHour = when (normalized % 12) {
            0 -> 12
            else -> normalized % 12
        }
        return "$displayHour $suffix"
    }

    return "${formatSingleHour(hour)}-${formatSingleHour(hour + 1)}"
}

// ─── Weekly Bar Chart (Canvas) ──────────────────────────────────────
@Composable
fun WeeklyBarChart(dailyCounts: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val maxCount = dailyCounts.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        val barWidth = size.width / (dailyCounts.size * 2f)
        val gap = barWidth

        dailyCounts.forEachIndexed { index, (_, count) ->
            val barHeight = (count.toFloat() / maxCount) * (size.height - 30.dp.toPx())
            val x = index * (barWidth + gap) + gap / 2

            // Bar with rounded top
            drawRoundRect(
                color = CatGreen,
                topLeft = Offset(x, size.height - 30.dp.toPx() - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
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
fun SourceDonutChart(sources: Map<String, Int>, modifier: Modifier = Modifier) {
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
                    size = Size(size.width - 24.dp.toPx(), size.height - 24.dp.toPx())
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                total.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CatText
            )
            Text(
                stringResource(R.string.stats_donut_total),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext
            )
        }
    }
}

// ─── Source Legend ──────────────────────────────────────────────────
@Composable
fun SourceLegend(sources: Map<String, Int>, modifier: Modifier = Modifier) {
    val total = sources.values.sum().coerceAtLeast(1)
    val colors = listOf(CatGreen, CatBlue, CatMauve, CatPeach, CatRed, CatYellow, CatTeal, CatLavender)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.entries.forEachIndexed { index, (source, count) ->
            val pct = (count * 100f / total).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(colors[index % colors.size], CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    friendlyMatchReason(source),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$count ($pct%)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors[index % colors.size]
                )
            }
        }
    }
}

@Composable
fun MiniStat(modifier: Modifier, label: String, value: String, color: Color) {
    PremiumCard(modifier = modifier, accentColor = color, cornerRadius = 14.dp) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = CatSubtext
            )
        }
    }
}
