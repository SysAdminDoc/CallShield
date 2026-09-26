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

/**
 * The required setup steps, counted the same way on Home and in Settings:
 * phone and message access, and the call screener when call blocking is on.
 * The spam database is data, not a grant, so Home checks it separately.
 */
internal data class SetupProgress(
    val done: Int,
    val total: Int,
) {
    val complete: Boolean get() = done == total
}

internal fun requiredSetupProgress(
    permissionsReady: Boolean,
    screenerReadyForCurrentMode: Boolean,
): SetupProgress = SetupProgress(done = listOf(permissionsReady, screenerReadyForCurrentMode).count { it }, total = 2)

/** The detection toggles Home counts as engines. */
internal data class EngineToggles(
    val stirShaken: Boolean,
    val heuristics: Boolean,
    val smsContent: Boolean,
    val neighborSpoof: Boolean,
    val mlScorer: Boolean,
    val rcsFilter: Boolean,
    val freqEscalation: Boolean,
)

/** Which ways in can reach the checks: screened calls, received texts, and RCS notifications. */
internal data class ProtectionPaths(
    val calls: Boolean,
    val texts: Boolean,
    val rcs: Boolean,
) {
    val messages: Boolean get() = texts || rcs
    val any: Boolean get() = calls || messages
}

internal fun protectionPaths(
    blockCallsEnabled: Boolean,
    callScreenerReady: Boolean,
    blockSmsEnabled: Boolean,
    smsPermissionsReady: Boolean,
    rcsFilter: Boolean,
    notificationAccessGranted: Boolean,
): ProtectionPaths =
    ProtectionPaths(
        calls = blockCallsEnabled && callScreenerReady,
        texts = blockSmsEnabled && smsPermissionsReady,
        rcs = blockSmsEnabled && rcsFilter && notificationAccessGranted,
    )

/**
 * Engines that can run right now. A toggle only counts when a call or message
 * can reach it: STIR/SHAKEN, neighbor spoofing and repeat-caller escalation
 * see screened calls, content rules see texts, and heuristics, the model and
 * the database see both. Home used to count every toggle plus the database.
 */
internal fun runnableEngineCount(
    toggles: EngineToggles,
    paths: ProtectionPaths,
    spamDatabaseReady: Boolean,
): Int =
    listOf(
        spamDatabaseReady && paths.any,
        toggles.stirShaken && paths.calls,
        toggles.heuristics && paths.any,
        toggles.smsContent && paths.messages,
        toggles.neighborSpoof && toggles.heuristics && paths.calls,
        toggles.mlScorer && paths.any,
        paths.rcs,
        toggles.freqEscalation && paths.calls,
    ).count { it }

/**
 * RCS filtering, push-alert caller trust and meeting mode all read
 * notifications, and two of them are on by default, so Home asks for access
 * whenever one of them is on without it.
 */
internal fun notificationAccessNeeded(
    rcsFilter: Boolean,
    blockSmsEnabled: Boolean,
    pushAlert: Boolean,
    meetingMode: Boolean,
    notificationAccessGranted: Boolean,
): Boolean = !notificationAccessGranted && ((rcsFilter && blockSmsEnabled) || pushAlert || meetingMode)

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

    val setupProgress = requiredSetupProgress(permissionsReady, screenerReadyForCurrentMode = !blockCallsEnabled || callScreenerReady)
    val requiredSetupComplete = setupProgress.done
    val requiredSetupTotal = setupProgress.total
    val optionalSetupComplete = listOf(overlayGranted, notificationsGranted).count { it }
    val optionalSetupTotal = 2
    val setupComplete = setupProgress.complete && spamDatabaseReady

    val heroMode =
        when {
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
