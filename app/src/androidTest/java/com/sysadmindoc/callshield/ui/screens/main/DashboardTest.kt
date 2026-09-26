package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.permissions.BackgroundExecutionRisk
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.setThemedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardScreenPassesAutomatedAccessibilityChecks() {
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
        composeRule.setThemedContent {
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
            )
        }

        composeRule.runStrictAccessibilityChecks()
    }

    @Test
    fun heroShowsProtectionStateSetupProgressAndSyncFreshness() {
        var syncRequests = 0
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
        val now = System.currentTimeMillis()

        composeRule.setContent {
            DashboardHeroCard(
                dashboardStatus = status,
                heroTitle = "Protection Active",
                heroSubtitle = "Calls and texts are actively protected.",
                requiredSetupComplete = 3,
                requiredSetupTotal = 3,
                engineCount = 8,
                lastSync = now,
                lastSyncSource = "",
                syncState = SyncState.Idle,
                heroAction =
                    HeroAction(
                        label = "Sync database",
                        icon = Icons.Default.Sync,
                        onClick = { syncRequests++ },
                    ),
                databaseCount = 1_234,
            )
        }

        composeRule.onNodeWithText("Protection Active").assertIsDisplayed()
        composeRule.onNodeWithText("Calls and texts are actively protected.").assertIsDisplayed()
        composeRule.onNodeWithText("Synced just now").assertIsDisplayed()
        composeRule.onNodeWithText("Setup").assertIsDisplayed()
        composeRule.onNodeWithText("3/3").assertIsDisplayed()
        composeRule.onNodeWithText("Engines").assertIsDisplayed()
        composeRule.onNodeWithText("8").assertIsDisplayed()
        composeRule.onNodeWithText("Numbers").assertIsDisplayed()
        composeRule.onNodeWithText("1,234").assertIsDisplayed()
        composeRule.onNodeWithText("Sync database").performClick()

        composeRule.runOnIdle {
            assertEquals(1, syncRequests)
        }
    }

    @Test
    fun statsRowRendersTodayAndTotalCounts() {
        composeRule.setContent {
            DashboardStatsRow(
                totalBlocked = 42,
                blockedToday = 7,
            )
        }

        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
        // The week's count is in the outcome summary; repeating it here was noise.
        composeRule.onNodeWithText("This week").assertDoesNotExist()
        composeRule.onNodeWithText("Total").assertIsDisplayed()
        composeRule.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun outcomeSummaryLeadsWithLocalizedCallAndTextCounts() {
        composeRule.setContent {
            DashboardOutcomeSummary(
                blockedCalls = 1_234,
                blockedTexts = 56,
            )
        }

        composeRule.onNodeWithText("This week", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("1,234").assertIsDisplayed()
        composeRule.onNodeWithText("56").assertIsDisplayed()
        composeRule.onNodeWithText("Calls blocked").assertIsDisplayed()
        composeRule.onNodeWithText("Texts blocked").assertIsDisplayed()
    }

    @Test
    fun completedSetupShowsEveryCheckReadyWithNoActions() {
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
            DashboardSetupChecklistCard(
                dashboardStatus = status,
                corePermissionsReady = true,
                syncState = SyncState.Idle,
                spamDatabaseReady = true,
                spamCount = 1_234,
                blockCallsEnabled = true,
                callScreenerReady = true,
                overlayGranted = true,
                notificationsGranted = true,
                onReviewPermissions = {},
                onSyncDatabase = {},
                onEnableCallScreener = {},
                onEnableOverlay = {},
                onEnableNotifications = {},
            )
        }

        composeRule.onNodeWithText("Protection checks", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Core permissions are granted.").assertIsDisplayed()
        composeRule.onNodeWithText("Ready for live call blocking.").assertIsDisplayed()
        composeRule.onNodeWithText("Spam numbers loaded: 1,234").assertIsDisplayed()
        listOf("Review", "Enable call screening", "Sync", "Optional extras", "Enable overlay", "Enable notifications").forEach { action ->
            composeRule.onAllNodesWithText(action).assertCountEquals(0)
        }
        // Every row carries its own Ready badge, and none says Needed.
        composeRule.onAllNodesWithText("Ready").assertCountEquals(3)
        composeRule.onAllNodesWithText("Needed").assertCountEquals(0)
    }

    @Test
    fun aMissingCheckWithNoActionSaysNeededOnItsOwnRow() {
        val status =
            buildDashboardStatusModel(
                blockCallsEnabled = true,
                blockSmsEnabled = true,
                callPermissionsReady = true,
                smsPermissionsReady = true,
                permissionsReady = true,
                spamDatabaseReady = true,
                callScreenerReady = false,
                overlayGranted = true,
                notificationsGranted = true,
            )

        composeRule.setContent {
            DashboardSetupChecklistCard(
                dashboardStatus = status,
                corePermissionsReady = true,
                syncState = SyncState.Idle,
                spamDatabaseReady = true,
                spamCount = 1_234,
                blockCallsEnabled = true,
                callScreenerReady = false,
                overlayGranted = true,
                notificationsGranted = true,
                onReviewPermissions = {},
                onSyncDatabase = {},
                // The phone can't be asked for the role, so the row can only say what's missing.
                onEnableCallScreener = null,
                onEnableOverlay = {},
                onEnableNotifications = {},
            )
        }

        composeRule.onAllNodesWithText("Ready").assertCountEquals(2)
        composeRule.onAllNodesWithText("Needed").assertCountEquals(1)
        // The badge sits on the call screener's row, level with its title and detail.
        val needed = composeRule.onNodeWithText("Needed").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Call screener").getUnclippedBoundsInRoot()
        val detail = composeRule.onNodeWithText("Required for live call blocking.").getUnclippedBoundsInRoot()
        val badgeMiddle = (needed.top + needed.bottom) / 2
        assertTrue("badge at $needed, row from ${title.top} to ${detail.bottom}", badgeMiddle > title.top && badgeMiddle < detail.bottom)
    }

    @Test
    fun setupChecklistShowsCallScreenerActionWhenRequired() {
        var screenerRequests = 0
        val status =
            buildDashboardStatusModel(
                blockCallsEnabled = true,
                blockSmsEnabled = true,
                callPermissionsReady = true,
                smsPermissionsReady = true,
                permissionsReady = true,
                spamDatabaseReady = true,
                callScreenerReady = false,
                overlayGranted = true,
                notificationsGranted = true,
            )

        composeRule.setContent {
            DashboardSetupChecklistCard(
                dashboardStatus = status,
                corePermissionsReady = true,
                syncState = SyncState.Idle,
                spamDatabaseReady = true,
                spamCount = 1200,
                blockCallsEnabled = true,
                callScreenerReady = false,
                overlayGranted = true,
                notificationsGranted = true,
                onReviewPermissions = {},
                onSyncDatabase = {},
                onEnableCallScreener = { screenerRequests++ },
                onEnableOverlay = {},
                onEnableNotifications = {},
            )
        }

        composeRule.onNodeWithText("Protection checks", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Call screener").assertIsDisplayed()
        composeRule.onNodeWithText("Required for live call blocking.").assertIsDisplayed()
        composeRule.onNodeWithText("Enable call screening").performClick()

        composeRule.runOnIdle {
            assertEquals(1, screenerRequests)
        }
    }

    @Test
    fun backgroundWarningNamesTheRiskAndOffersRecovery() {
        var batterySettingsRequests = 0
        var dismissRequests = 0

        composeRule.setContent {
            BackgroundExecutionWarning(
                risk = BackgroundExecutionRisk.BackgroundRestricted,
                showMiuiAction = false,
                onOpenBatterySettings = { batterySettingsRequests++ },
                onOpenMiuiSettings = {},
                onDismiss = { dismissRequests++ },
            )
        }

        composeRule.onNodeWithText("Background activity is restricted").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open battery settings").performClick()
        composeRule.onNodeWithContentDescription("Dismiss").performClick()

        composeRule.runOnIdle {
            assertEquals(1, batterySettingsRequests)
            assertEquals(1, dismissRequests)
        }
    }
}
