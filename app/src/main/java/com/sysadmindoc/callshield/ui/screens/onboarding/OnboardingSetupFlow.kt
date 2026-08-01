package com.sysadmindoc.callshield.ui.screens.onboarding

internal enum class OnboardingSetupStep {
    Intro,
    RuntimePermissions,
    CallScreening,
    Notifications,
    Overlay,
    NotificationAccess,
    Review,
}

internal data class OnboardingSetupState(
    val runtimePermissionsGranted: Boolean,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
    val notificationAccessGranted: Boolean,
    val screenerGranted: Boolean,
    val screenerSupported: Boolean,
) {
    fun isComplete(step: OnboardingSetupStep): Boolean =
        when (step) {
            OnboardingSetupStep.Intro,
            OnboardingSetupStep.Review,
            -> true

            OnboardingSetupStep.RuntimePermissions -> runtimePermissionsGranted

            OnboardingSetupStep.CallScreening -> !screenerSupported || screenerGranted

            OnboardingSetupStep.Notifications -> notificationsGranted

            OnboardingSetupStep.Overlay -> overlayGranted

            OnboardingSetupStep.NotificationAccess -> notificationAccessGranted
        }

    val completedSetupCount: Int
        get() = setupSteps.count(::isComplete)

    val isReady: Boolean
        get() = completedSetupCount == setupSteps.size
}

internal val onboardingSteps = OnboardingSetupStep.entries

internal val setupSteps =
    listOf(
        OnboardingSetupStep.RuntimePermissions,
        OnboardingSetupStep.CallScreening,
        OnboardingSetupStep.Notifications,
        OnboardingSetupStep.Overlay,
        OnboardingSetupStep.NotificationAccess,
    )
