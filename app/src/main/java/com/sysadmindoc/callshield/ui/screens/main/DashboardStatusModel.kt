package com.sysadmindoc.callshield.ui.screens.main

internal enum class DashboardHeroMode {
    Active,
    SetupNeeded,
    Disabled,
}

internal data class DashboardStatusModel(
    val protectionEnabled: Boolean,
    val shieldActive: Boolean,
    val permissionsReady: Boolean,
    val callProtectionReady: Boolean,
    val smsProtectionReady: Boolean,
    val requiredSetupComplete: Int,
    val requiredSetupTotal: Int,
    val optionalSetupComplete: Int,
    val optionalSetupTotal: Int,
    val setupComplete: Boolean,
    val heroMode: DashboardHeroMode,
)

internal fun buildDashboardStatusModel(
    blockCallsEnabled: Boolean,
    blockSmsEnabled: Boolean,
    callPermissionsReady: Boolean,
    smsPermissionsReady: Boolean,
    permissionsReady: Boolean,
    spamDatabaseReady: Boolean,
    callScreenerReady: Boolean,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
): DashboardStatusModel {
    val protectionEnabled = blockCallsEnabled || blockSmsEnabled
    val callProtectionReady = !blockCallsEnabled || (callPermissionsReady && spamDatabaseReady && callScreenerReady)
    val smsProtectionReady = !blockSmsEnabled || (smsPermissionsReady && spamDatabaseReady)
    val shieldActive = protectionEnabled && callProtectionReady && smsProtectionReady

    val screenerReadyForCurrentMode = !blockCallsEnabled || callScreenerReady
    val requiredSetupComplete = listOf(
        permissionsReady,
        spamDatabaseReady,
        screenerReadyForCurrentMode,
    ).count { it }
    val requiredSetupTotal = 3
    val optionalSetupComplete = listOf(overlayGranted, notificationsGranted).count { it }
    val optionalSetupTotal = 2
    val setupComplete = requiredSetupComplete == requiredSetupTotal

    val heroMode = when {
        shieldActive -> DashboardHeroMode.Active
        protectionEnabled && !setupComplete -> DashboardHeroMode.SetupNeeded
        else -> DashboardHeroMode.Disabled
    }

    return DashboardStatusModel(
        protectionEnabled = protectionEnabled,
        shieldActive = shieldActive,
        permissionsReady = permissionsReady,
        callProtectionReady = callProtectionReady,
        smsProtectionReady = smsProtectionReady,
        requiredSetupComplete = requiredSetupComplete,
        requiredSetupTotal = requiredSetupTotal,
        optionalSetupComplete = optionalSetupComplete,
        optionalSetupTotal = optionalSetupTotal,
        setupComplete = setupComplete,
        heroMode = heroMode,
    )
}
