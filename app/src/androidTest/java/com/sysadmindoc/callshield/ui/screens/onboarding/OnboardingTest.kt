package com.sysadmindoc.callshield.ui.screens.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingScreenPassesAutomatedAccessibilityChecks() {
        setOnboardingContent(setupState = readyState())

        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun guidedSetupVisitsEveryStepAndCompletes() {
        var completed = 0
        setOnboardingContent(
            setupState = readyState(),
            onComplete = { completed++ },
        )

        val expectedTitles =
            listOf(
                "Set up protection",
                "Phone & messages",
                "Call screening",
                "Protection alerts",
                "Caller ID overlay",
                "Notification access",
                "Protection is ready",
            )
        expectedTitles.forEachIndexed { index, title ->
            assertPage(index + 1, title)
            if (index < expectedTitles.lastIndex) {
                composeRule.onNodeWithTag(primaryTag(onboardingSteps[index])).performClick()
            }
        }

        composeRule.onNodeWithTag(ONBOARDING_FINISH_BUTTON_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, completed) }
    }

    @Test
    fun runtimePermissionStepWaitsForAndroidVerificationThenAdvances() {
        var state by mutableStateOf(readyState().copy(runtimePermissionsGranted = false))
        var requests = 0
        composeRule.setContent {
            OnboardingScreenContent(
                setupState = state,
                onRequestRuntimePermissions = {
                    requests++
                    state = state.copy(runtimePermissionsGranted = true)
                },
                onRequestScreener = {},
                onRequestNotifications = {},
                onRequestOverlay = {},
                onRequestNotificationAccess = {},
                onComplete = {},
            )
        }

        composeRule.onNodeWithTag(ONBOARDING_PRIMARY_ACTION_TAG).performClick()
        assertPage(2, "Phone & messages")
        composeRule.onNodeWithTag(ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG).performClick()

        composeRule.waitForIdle()
        assertPage(3, "Call screening")
        assertEquals(1, requests)
    }

    @Test
    fun reviewRoutesBackToASettingRevokedDuringSetup() {
        var state by mutableStateOf(readyState())
        var completed = 0
        composeRule.setContent {
            OnboardingScreenContent(
                setupState = state,
                onRequestRuntimePermissions = {},
                onRequestScreener = {},
                onRequestNotifications = {},
                onRequestOverlay = {},
                onRequestNotificationAccess = {},
                onComplete = { completed++ },
            )
        }

        repeat(onboardingSteps.lastIndex) {
            composeRule.onNodeWithTag(primaryTag(onboardingSteps[it])).performClick()
        }
        assertPage(7, "Protection is ready")

        composeRule.runOnIdle { state = state.copy(overlayGranted = false) }
        composeRule.onNodeWithText("Fix missing access").performClick()

        assertPage(5, "Caller ID overlay")
        assertEquals(0, completed)
    }

    @Test
    fun unsupportedCallScreeningIsClearlySkipped() {
        val state = readyState().copy(screenerGranted = false, screenerSupported = false)
        setOnboardingContent(setupState = state)

        composeRule.onNodeWithTag(ONBOARDING_PRIMARY_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG).performClick()
        assertPage(3, "Call screening")
        composeRule.onNodeWithText("Not supported on this device · safely skipped").assertIsDisplayed()
    }

    private fun setOnboardingContent(
        setupState: OnboardingSetupState,
        onComplete: () -> Unit = {},
    ) {
        composeRule.setContent {
            OnboardingScreenContent(
                setupState = setupState,
                onRequestRuntimePermissions = {},
                onRequestScreener = {},
                onRequestNotifications = {},
                onRequestOverlay = {},
                onRequestNotificationAccess = {},
                onComplete = onComplete,
            )
        }
    }

    private fun assertPage(
        page: Int,
        title: String,
    ) {
        composeRule.onNodeWithContentDescription("Onboarding page $page of 7").assertIsDisplayed()
        composeRule.onAllNodesWithText(title)[0].assertIsDisplayed()
    }

    private fun readyState() =
        OnboardingSetupState(
            runtimePermissionsGranted = true,
            notificationsGranted = true,
            overlayGranted = true,
            notificationAccessGranted = true,
            screenerGranted = true,
            screenerSupported = true,
        )
}
