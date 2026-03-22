package com.sysadmindoc.callshield.ui.screens.stats

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun StatsScreen(viewModel: MainViewModel) {
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    val totalBlocked by viewModel.totalBlocked.collectAsState()
    val spamCount by viewModel.spamCount.collectAsState()

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

    // Weekly trend (last 7 days)
    val now = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val weeklyData = (0..6).map { daysAgo ->
        val dayStart = now - (daysAgo + 1) * dayMs
        val dayEnd = now - daysAgo * dayMs
        blockedCalls.count { it.timestamp in dayStart..dayEnd }
    }.reversed()
    val maxWeekly = weeklyData.max().coerceAtLeast(1)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(Modifier.weight(1f), "Calls", callsOnly.size.toString(), CatRed)
            MiniStat(Modifier.weight(1f), "SMS", smsOnly.size.toString(), CatMauve)
            MiniStat(Modifier.weight(1f), "DB Size", spamCount.toString(), CatGreen)
        }

        // Weekly bar chart
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Last 7 Days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    val todayIndex = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                    weeklyData.forEachIndexed { i, count ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val height = (count.toFloat() / maxWeekly * 80).dp.coerceAtLeast(4.dp)
                            val animatedHeight by animateDpAsState(targetValue = height, animationSpec = spring(dampingRatio = 0.6f), label = "bar")
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                            Spacer(Modifier.height(4.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.6f).height(animatedHeight).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(CatBlue))
                        }
                    }
                }
            }
        }

        // Type breakdown
        if (typeBreakdown.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("By Detection Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    typeBreakdown.take(8).forEach { (type, count) ->
                        val fraction = count.toFloat() / totalBlocked.coerceAtLeast(1)
                        val color = when {
                            "database" in type -> CatGreen
                            "heuristic" in type -> CatBlue
                            "sms_content" in type -> CatMauve
                            "stir" in type -> CatYellow
                            "prefix" in type -> CatPeach
                            "user" in type -> CatRed
                            "wildcard" in type -> CatYellow
                            "time" in type -> CatMauve
                            "frequency" in type -> CatPeach
                            else -> CatSubtext
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(type.replace("_", " ").replaceFirstChar { it.uppercase() }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("$count", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = color, trackColor = CatOverlay.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Top offenders
        if (topOffenders.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Top Offenders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    topOffenders.forEachIndexed { i, (number, count) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.", color = CatOverlay, modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall)
                            Text(PhoneFormatter.format(number), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("${count}x", color = CatRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (blockedCalls.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BarChart, null, tint = CatOverlay, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No data yet", color = CatSubtext)
                    Text("Stats will appear after calls are blocked", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun MiniStat(modifier: Modifier, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
        }
    }
}
