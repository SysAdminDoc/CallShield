package com.sysadmindoc.callshield.ui.screens.onboarding

import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.permissions.PermissionCapabilityId
import com.sysadmindoc.callshield.permissions.PermissionCapabilityPriority

internal enum class OnboardingSetupStep {
    Intro,
    RuntimePermissions,
    CallScreening,
    Notifications,
    Overlay,
    NotificationAccess,
    Review,
    Profile,
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
            OnboardingSetupStep.Profile,
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
        get() = setupSteps.filterNot { it in optionalSetupSteps }.all(::isComplete)
}

internal val onboardingSteps = OnboardingSetupStep.entries

/**
 * The Android release from which a step's permission is a restricted setting
 * for an app installed outside an app store: notification access from
 * Android 13, the overlay and the SMS permissions from Android 15. Until the
 * user allows restricted settings in App info, the grant screen refuses, so
 * offering it again changes nothing. Call screening and notifications aren't
 * restricted.
 */
private fun restrictedFromSdk(step: OnboardingSetupStep): Int? =
    when (step) {
        OnboardingSetupStep.NotificationAccess -> ANDROID_13

        OnboardingSetupStep.Overlay, OnboardingSetupStep.RuntimePermissions -> ANDROID_15

        OnboardingSetupStep.Intro,
        OnboardingSetupStep.CallScreening,
        OnboardingSetupStep.Notifications,
        OnboardingSetupStep.Review,
        OnboardingSetupStep.Profile,
        -> null
    }

private const val ANDROID_13 = 33
private const val ANDROID_15 = 35

/**
 * Whether the step on screen should point at Allow restricted settings: the
 * user was sent to grant it, came back without it, and this Android release
 * can restrict it. GrapheneOS and Calyx users hit this (issue #21).
 */
internal fun restrictedSettingsHintApplies(
    step: OnboardingSetupStep,
    awaitingStep: OnboardingSetupStep?,
    state: OnboardingSetupState,
    sdkInt: Int,
): Boolean {
    val restrictedFrom = restrictedFromSdk(step) ?: return false
    return sdkInt >= restrictedFrom && awaitingStep == step && !state.isComplete(step)
}

internal val setupSteps =
    listOf(
        OnboardingSetupStep.RuntimePermissions,
        OnboardingSetupStep.CallScreening,
        OnboardingSetupStep.Notifications,
        OnboardingSetupStep.Overlay,
        OnboardingSetupStep.NotificationAccess,
    )

internal val optionalSetupSteps: Set<OnboardingSetupStep> =
    CallShieldPermissions.permissionCapabilityContracts
        .filter { it.priority == PermissionCapabilityPriority.Recommended }
        .mapNotNull { contract ->
            when (contract.id) {
                PermissionCapabilityId.PostNotifications -> OnboardingSetupStep.Notifications
                PermissionCapabilityId.Overlay -> OnboardingSetupStep.Overlay
                PermissionCapabilityId.NotificationAccess -> OnboardingSetupStep.NotificationAccess
                else -> null
            }
        }.toSet()
