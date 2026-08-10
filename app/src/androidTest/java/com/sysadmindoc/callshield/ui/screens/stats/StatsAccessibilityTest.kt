package com.sysadmindoc.callshield.ui.screens.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Rule
import org.junit.Test

class StatsAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statisticsChartLegendAndSummaryPassStrictAccessibilityChecks() {
        val sources =
            linkedMapOf(
                BlockReasonCode.DATABASE to 8,
                BlockReasonCode.HEURISTIC to 3,
                BlockReasonCode.USER_BLOCKLIST to 1,
            )
        composeRule.setContent {
            Column {
                MiniStat(modifier = Modifier, label = "Total blocked", value = "12", color = CatGreen)
                WeeklyBarChart(
                    dailyCounts = listOf("Mon" to 2, "Tue" to 4, "Wed" to 1),
                )
                SourceDonutChart(sources)
                SourceLegend(sources)
            }
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
