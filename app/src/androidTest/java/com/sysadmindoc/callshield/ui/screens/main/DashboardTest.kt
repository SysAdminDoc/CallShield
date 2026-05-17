package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DashboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun heroShowsProtectionStateSetupProgressAndSyncFreshness() {
        var syncRequests = 0
        val status = buildDashboardStatusModel(
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
                shieldActive = true,
                heroTitle = "Protection Active",
                heroSubtitle = "Calls and texts are actively protected.",
                heroAccent = CatGreen,
                requiredSetupComplete = 3,
                requiredSetupTotal = 3,
                setupSummary = "Setup complete",
                engineCount = 8,
                aggressiveMode = false,
                lastSync = now,
                lastSyncSource = "",
                syncState = SyncState.Idle,
                heroAction = HeroAction(
                    label = "Sync Database",
                    icon = Icons.Default.Sync,
                    onClick = { syncRequests++ },
                ),
            )
        }

        composeRule.onNodeWithText("Protection Active").assertIsDisplayed()
        composeRule.onNodeWithText("Calls and texts are actively protected.").assertIsDisplayed()
        composeRule.onNodeWithText("Core setup ready: 3/3").assertIsDisplayed()
        composeRule.onNodeWithText("Protection engines active: 8").assertIsDisplayed()
        composeRule.onNodeWithText("Synced just now").assertIsDisplayed()
        composeRule.onNodeWithText("Sync Database").performClick()

        composeRule.runOnIdle {
            assertEquals(1, syncRequests)
        }
    }

    @Test
    fun statsRowRendersTodayWeekAndTotalCounts() {
        composeRule.setContent {
            DashboardStatsRow(
                totalBlocked = 42,
                blockedToday = 7,
                blockedThisWeek = 12,
            )
        }

        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
        composeRule.onNodeWithText("This Week").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("Total").assertIsDisplayed()
        composeRule.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun setupChecklistShowsCallScreenerActionWhenRequired() {
        var screenerRequests = 0
        val status = buildDashboardStatusModel(
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
                setupSummary = "Complete the core steps below to enable full protection.",
                corePermissionsReady = true,
                missingPermissionCount = 0,
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

        composeRule.onNodeWithText("Setup Checklist").assertIsDisplayed()
        composeRule.onNodeWithText("Call screener").assertIsDisplayed()
        composeRule.onNodeWithText("Set CallShield as the default call screener for live call blocking.").assertIsDisplayed()
        composeRule.onNodeWithText("Enable Call Screening").performClick()

        composeRule.runOnIdle {
            assertEquals(1, screenerRequests)
        }
    }
}
