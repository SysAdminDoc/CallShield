package com.sysadmindoc.callshield.ui.screens.onboarding

import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun walkthroughVisitsFourPagesAndCompletes() {
        var completed = 0
        setOnboardingContent(
            permsGranted = true,
            notificationsGranted = true,
            overlayGranted = true,
            screenerGranted = true,
            onComplete = { completed++ },
        )

        assertPage(1, "Welcome to CallShield")
        composeRule.onNodeWithText("Next").performClick()

        assertPage(2, "Grant Permissions")
        composeRule.onNodeWithText("Next").performClick()

        assertPage(3, "Set as Call Screener")
        composeRule.onNodeWithText("Next").performClick()

        assertPage(4, "Stay Updated")
        composeRule.onNodeWithText("Finish Setup").performClick()

        composeRule.runOnIdle {
            assertEquals(1, completed)
        }
    }

    @Test
    fun permissionPageExposesCoreAndOptionalRequestAffordances() {
        var coreRequests = 0
        var notificationRequests = 0
        var overlayRequests = 0
        setOnboardingContent(
            permsGranted = false,
            notificationsGranted = false,
            overlayGranted = false,
            screenerGranted = false,
            onRequestCorePermissions = { coreRequests++ },
            onRequestNotifications = { notificationRequests++ },
            onRequestOverlay = { overlayRequests++ },
        )

        composeRule.onNodeWithText("Next").performClick()

        composeRule
            .onNodeWithTag(ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            composeRule
                .onNodeWithTag(ONBOARDING_NOTIFICATIONS_BUTTON_TAG)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        } else {
            composeRule.onAllNodesWithTag(ONBOARDING_NOTIFICATIONS_BUTTON_TAG).assertCountEquals(0)
        }

        composeRule
            .onNodeWithTag(ONBOARDING_OVERLAY_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, coreRequests)
            assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 1 else 0, notificationRequests)
            assertEquals(1, overlayRequests)
        }
    }

    @Test
    fun screenerPageExposesSupportedSetupAction() {
        var screenerRequests = 0
        setOnboardingContent(
            permsGranted = false,
            notificationsGranted = false,
            overlayGranted = false,
            screenerGranted = false,
            screenerSupported = true,
            onRequestScreener = { screenerRequests++ },
        )

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Next").performClick()

        composeRule
            .onNodeWithTag(ONBOARDING_SCREENER_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, screenerRequests)
        }
    }

    @Test
    fun screenerPageShowsUnavailableStateWithoutSetupButton() {
        setOnboardingContent(
            permsGranted = false,
            notificationsGranted = false,
            overlayGranted = false,
            screenerGranted = false,
            screenerSupported = false,
        )

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Next").performClick()

        composeRule
            .onNodeWithText("Call screening unavailable on this device")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag(ONBOARDING_SCREENER_BUTTON_TAG).assertCountEquals(0)
    }

    private fun setOnboardingContent(
        permsGranted: Boolean,
        notificationsGranted: Boolean,
        overlayGranted: Boolean,
        screenerGranted: Boolean,
        screenerSupported: Boolean = true,
        onRequestCorePermissions: () -> Unit = {},
        onRequestNotifications: () -> Unit = {},
        onRequestOverlay: () -> Unit = {},
        onRequestScreener: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        composeRule.setContent {
            OnboardingScreenContent(
                permsGranted = permsGranted,
                notificationsGranted = notificationsGranted,
                overlayGranted = overlayGranted,
                screenerGranted = screenerGranted,
                screenerSupported = screenerSupported,
                onRequestCorePermissions = onRequestCorePermissions,
                onRequestNotifications = onRequestNotifications,
                onRequestOverlay = onRequestOverlay,
                onRequestScreener = onRequestScreener,
                onComplete = onComplete,
            )
        }
    }

    private fun assertPage(
        page: Int,
        title: String,
    ) {
        composeRule.onNodeWithContentDescription("Onboarding page $page of 4").assertIsDisplayed()
        composeRule.onAllNodesWithText(title)[0].assertIsDisplayed()
    }
}
