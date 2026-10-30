package com.sysadmindoc.callshield.ui.screens.main

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsSummaryCard
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each stat tile is one TalkBack stop that reads its value with its label. Unmerged,
 * TalkBack read every value in a row first and every label after, so "3/3, 8, 1,234,
 * Setup, Engines, Numbers" paired nothing with anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StatTileTalkBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun assertTile(
        value: String,
        label: String,
    ) {
        composeRule.onNode(hasText(label)).assertTextEquals(value, label)
    }

    @Test
    fun `Home readiness tiles read value and label together`() {
        val status =
            buildDashboardStatusModel(
                blockCallsEnabled = true,
                blockSmsEnabled = true,
                callPermissionsReady = true,
                smsPermissionsReady = true,
                permissionsReady = true,
                spamDatabaseReady = true,
                callScreenerReady = true,
                overlayGranted = true,
                notificationsGranted = true,
            )
        composeRule.setContent {
            CallShieldTheme {
                DashboardHeroCard(
                    dashboardStatus = status,
                    heroTitle = "Protection Active",
                    heroSubtitle = "Calls and texts are actively protected.",
                    requiredSetupComplete = 3,
                    requiredSetupTotal = 3,
                    engineCount = 8,
                    lastSync = System.currentTimeMillis(),
                    lastSyncSource = "",
                    syncState = SyncState.Idle,
                    heroAction = null,
                    databaseCount = 1_234,
                )
            }
        }

        assertTile("3/3", context.getString(R.string.dashboard_metric_core_setup))
        assertTile("8", context.getString(R.string.dashboard_metric_engines))
        assertTile("1,234", context.getString(R.string.dashboard_metric_numbers))
    }

    @Test
    fun `Home outcome and stat tiles read value and label together`() {
        composeRule.setContent {
            CallShieldTheme {
                DashboardOutcomeSummary(blockedCalls = 12, blockedTexts = 5)
                DashboardStatsRow(totalBlocked = 42, blockedToday = 7)
            }
        }
        composeRule.waitForIdle()

        assertTile("12", context.getString(R.string.dashboard_outcome_calls))
        assertTile("5", context.getString(R.string.dashboard_outcome_texts))
        assertTile("7", context.getString(R.string.dashboard_stat_today))
        assertTile("42", context.getString(R.string.dashboard_stat_total))
    }

    @Test
    fun `Activity summary pills read count and label together`() {
        composeRule.setContent {
            CallShieldTheme {
                RecentCallsSummaryCard(
                    totalCount = 20,
                    spamCount = 4,
                    missedCount = 3,
                    contactCount = 9,
                    refreshing = false,
                    onRefresh = {},
                )
            }
        }

        assertTile("20", context.getString(R.string.recent_summary_total))
        assertTile("4", context.getString(R.string.recent_summary_spam))
        assertTile("3", context.getString(R.string.recent_summary_missed))
        assertTile("9", context.getString(R.string.recent_summary_known))
    }

    @Test
    fun `a Database entry is one stop with its type and report count`() {
        composeRule.setContent {
            CallShieldTheme {
                DatabaseItem(SpamNumber(number = "+12025550143", type = "custom_list", reports = 3))
            }
        }

        val reports = context.resources.getQuantityString(R.plurals.blocklist_reports, 3, 3)
        composeRule.onNode(hasText("Custom list")).assert(hasText(reports))
    }
}
