package com.sysadmindoc.callshield.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.service.RcsNotificationListener
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatMauve
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatTeal
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.launch

internal const val ONBOARDING_PRIMARY_ACTION_TAG = "onboarding_primary_action"
internal const val ONBOARDING_FINISH_BUTTON_TAG = "onboarding_finish_button"
internal const val ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG = "onboarding_core_permissions_button"
internal const val ONBOARDING_NOTIFICATIONS_BUTTON_TAG = "onboarding_notifications_button"
internal const val ONBOARDING_OVERLAY_BUTTON_TAG = "onboarding_overlay_button"
internal const val ONBOARDING_SCREENER_BUTTON_TAG = "onboarding_screener_button"
internal const val ONBOARDING_NOTIFICATION_ACCESS_BUTTON_TAG = "onboarding_notification_access_button"

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val roleManager = remember { context.getSystemService(Context.ROLE_SERVICE) as? RoleManager }
    val screenerSupported = remember(roleManager) { CallShieldPermissions.isCallScreeningRoleAvailable(roleManager) }
    var runtimePermissionsGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.hasOnboardingRuntimePermissions(context))
    }
    var notificationsGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.hasNotificationPermission(context))
    }
    var overlayGranted by remember(context) { mutableStateOf(CallShieldPermissions.canDrawOverlays(context)) }
    var notificationAccessGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.hasNotificationListenerAccess(context))
    }
    var screenerGranted by remember(roleManager) {
        mutableStateOf(CallShieldPermissions.hasCallScreeningRole(roleManager))
    }
    var runtimePermissionsBlocked by rememberSaveable { mutableStateOf(false) }
    var runtimePermissionRequestAttempts by rememberSaveable { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refreshReadiness() {
        runtimePermissionsGranted = CallShieldPermissions.hasOnboardingRuntimePermissions(context)
        notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
        overlayGranted = CallShieldPermissions.canDrawOverlays(context)
        notificationAccessGranted = CallShieldPermissions.hasNotificationListenerAccess(context)
        screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
    }

    val runtimePermissionsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshReadiness()
            val activity = context as? Activity
            val missing = CallShieldPermissions.missingOnboardingRuntimePermissions(context)
            runtimePermissionsBlocked =
                missing.isNotEmpty() &&
                runtimePermissionRequestAttempts >= 2 &&
                activity != null &&
                missing.none(activity::shouldShowRequestPermissionRationale)
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshReadiness()
        }
    val screeningLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshReadiness()
        }

    DisposableEffect(lifecycleOwner, context, roleManager) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshReadiness()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val couldNotOpenMessage = stringResource(R.string.onboarding_could_not_open_settings)

    fun reportLaunchFailure() {
        scope.launch { snackbarHostState.showSnackbar(couldNotOpenMessage) }
    }

    OnboardingScreenContent(
        setupState =
            OnboardingSetupState(
                runtimePermissionsGranted = runtimePermissionsGranted,
                notificationsGranted = notificationsGranted,
                overlayGranted = overlayGranted,
                notificationAccessGranted = notificationAccessGranted,
                screenerGranted = screenerGranted,
                screenerSupported = screenerSupported,
            ),
        runtimePermissionsBlocked = runtimePermissionsBlocked,
        snackbarHostState = snackbarHostState,
        onRequestRuntimePermissions = {
            if (runtimePermissionsBlocked) {
                context.startActivitySafely(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                    onFailure = ::reportLaunchFailure,
                )
            } else {
                runtimePermissionRequestAttempts++
                runtimePermissionsLauncher.launch(CallShieldPermissions.onboardingRuntimePermissions.toTypedArray())
            }
        },
        onRequestScreener = {
            val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            if (intent == null) {
                reportLaunchFailure()
            } else {
                try {
                    screeningLauncher.launch(intent)
                } catch (_: Exception) {
                    reportLaunchFailure()
                }
            }
        },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivitySafely(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    },
                    onFailure = ::reportLaunchFailure,
                )
            }
        },
        onRequestOverlay = {
            context.startActivitySafely(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
                onFailure = ::reportLaunchFailure,
            )
        },
        onRequestNotificationAccess = {
            val notificationAccessIntent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(context, RcsNotificationListener::class.java).flattenToString(),
                        )
                    }
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
            context.startActivitySafely(
                notificationAccessIntent,
                onFailure = ::reportLaunchFailure,
            )
        },
        onComplete = onComplete,
    )
}

@Composable
internal fun OnboardingScreenContent(
    setupState: OnboardingSetupState,
    onRequestRuntimePermissions: () -> Unit,
    onRequestScreener: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onComplete: () -> Unit,
    runtimePermissionsBlocked: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var awaitingStep by rememberSaveable { mutableStateOf<OnboardingSetupStep?>(null) }
    val currentStep = onboardingSteps[currentPage]
    val presentation = onboardingPresentation(currentStep, setupState, runtimePermissionsBlocked)
    val pageContentDescription =
        stringResource(R.string.cd_onboarding_page, currentPage + 1, onboardingSteps.size)

    LaunchedEffect(awaitingStep, setupState) {
        val requestedStep = awaitingStep
        if (requestedStep != null && setupState.isComplete(requestedStep)) {
            awaitingStep = null
            if (currentStep == requestedStep && currentPage < onboardingSteps.lastIndex) currentPage++
        }
    }

    fun runPrimaryAction() {
        when (currentStep) {
            OnboardingSetupStep.Intro -> {
                currentPage++
            }

            OnboardingSetupStep.Review -> {
                if (setupState.isReady) {
                    onComplete()
                } else {
                    val missingStep = setupSteps.firstOrNull { !setupState.isComplete(it) }
                    currentPage = onboardingSteps.indexOf(missingStep).coerceAtLeast(1)
                }
            }

            else -> {
                if (setupState.isComplete(currentStep)) {
                    currentPage++
                } else {
                    awaitingStep = currentStep
                    when (currentStep) {
                        OnboardingSetupStep.RuntimePermissions -> onRequestRuntimePermissions()

                        OnboardingSetupStep.CallScreening -> onRequestScreener()

                        OnboardingSetupStep.Notifications -> onRequestNotifications()

                        OnboardingSetupStep.Overlay -> onRequestOverlay()

                        OnboardingSetupStep.NotificationAccess -> onRequestNotificationAccess()

                        OnboardingSetupStep.Intro,
                        OnboardingSetupStep.Review,
                        -> Unit
                    }
                }
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_setup_label),
                style = MaterialTheme.typography.labelLarge,
                color = CatSubtext,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_ready_count, setupState.completedSetupCount, setupSteps.size),
                style = MaterialTheme.typography.labelMedium,
                color = if (setupState.isReady) CatGreen else CatSubtext,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (currentPage + 1) / onboardingSteps.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = presentation.accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            text = stringResource(R.string.onboarding_step, currentPage + 1, onboardingSteps.size),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = CatOverlay,
        )

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding_step",
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .semantics { contentDescription = pageContentDescription },
        ) { step ->
            val stepPresentation = onboardingPresentation(step, setupState, runtimePermissionsBlocked)
            OnboardingStepBody(
                step = step,
                presentation = stepPresentation,
                setupState = setupState,
            )
        }

        SnackbarHost(snackbarHostState)
        Spacer(Modifier.height(8.dp))
        if (currentPage > 0) {
            TextButton(
                onClick = {
                    awaitingStep = null
                    currentPage--
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(7.dp))
                Text(stringResource(R.string.onboarding_back))
            }
            Spacer(Modifier.height(4.dp))
        }
        PremiumActionButton(
            label = primaryLabel(currentStep, setupState, runtimePermissionsBlocked),
            icon = primaryIcon(currentStep, setupState),
            color = presentation.accent,
            onClick = ::runPrimaryAction,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(primaryTag(currentStep)),
        )
    }
}

private data class OnboardingPresentation(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val instruction: String?,
    val accent: Color,
)

@Composable
private fun onboardingPresentation(
    step: OnboardingSetupStep,
    setupState: OnboardingSetupState,
    runtimePermissionsBlocked: Boolean,
): OnboardingPresentation =
    when (step) {
        OnboardingSetupStep.Intro -> {
            OnboardingPresentation(
                Icons.Default.Shield,
                stringResource(R.string.onboarding_guided_title),
                stringResource(R.string.onboarding_guided_body),
                null,
                CatTeal,
            )
        }

        OnboardingSetupStep.RuntimePermissions -> {
            OnboardingPresentation(
                Icons.Default.Security,
                stringResource(R.string.onboarding_runtime_title),
                stringResource(R.string.onboarding_runtime_body),
                if (runtimePermissionsBlocked) {
                    stringResource(R.string.onboarding_runtime_settings_instruction)
                } else {
                    stringResource(R.string.onboarding_runtime_instruction)
                },
                CatBlue,
            )
        }

        OnboardingSetupStep.CallScreening -> {
            OnboardingPresentation(
                Icons.AutoMirrored.Filled.PhoneCallback,
                stringResource(R.string.onboarding_call_screening_title),
                if (setupState.screenerSupported) {
                    stringResource(R.string.onboarding_call_screening_body)
                } else {
                    stringResource(R.string.onboarding_screener_unavailable_detail)
                },
                if (setupState.screenerSupported) {
                    stringResource(R.string.onboarding_call_screening_instruction)
                } else {
                    null
                },
                CatMauve,
            )
        }

        OnboardingSetupStep.Notifications -> {
            OnboardingPresentation(
                Icons.Default.Notifications,
                stringResource(R.string.onboarding_notifications_title),
                stringResource(R.string.onboarding_notifications_body),
                stringResource(R.string.onboarding_notifications_instruction),
                CatPeach,
            )
        }

        OnboardingSetupStep.Overlay -> {
            OnboardingPresentation(
                Icons.Default.Layers,
                stringResource(R.string.onboarding_overlay_title),
                stringResource(R.string.onboarding_overlay_body),
                stringResource(R.string.onboarding_overlay_instruction),
                CatBlue,
            )
        }

        OnboardingSetupStep.NotificationAccess -> {
            OnboardingPresentation(
                Icons.Default.NotificationsActive,
                stringResource(R.string.onboarding_notification_access_title),
                stringResource(R.string.onboarding_notification_access_body),
                stringResource(R.string.onboarding_notification_access_instruction),
                CatYellow,
            )
        }

        OnboardingSetupStep.Review -> {
            OnboardingPresentation(
                Icons.Default.VerifiedUser,
                if (setupState.isReady) {
                    stringResource(R.string.onboarding_review_ready_title)
                } else {
                    stringResource(R.string.onboarding_review_missing_title)
                },
                if (setupState.isReady) {
                    stringResource(R.string.onboarding_review_ready_body)
                } else {
                    stringResource(R.string.onboarding_review_missing_body)
                },
                null,
                if (setupState.isReady) CatGreen else CatYellow,
            )
        }
    }

@Composable
private fun OnboardingStepBody(
    step: OnboardingSetupStep,
    presentation: OnboardingPresentation,
    setupState: OnboardingSetupState,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (step == OnboardingSetupStep.Intro) {
            Image(
                painter = painterResource(R.drawable.ic_callshield_brand_art),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(72.dp),
            )
        } else {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = presentation.accent,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CatText,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = presentation.body,
            style = MaterialTheme.typography.bodyLarge,
            color = CatSubtext,
        )
        Spacer(Modifier.height(22.dp))

        when (step) {
            OnboardingSetupStep.Intro -> {
                OnboardingIntroDetails()
            }

            OnboardingSetupStep.Review -> {
                OnboardingReview(setupState)
            }

            else -> {
                OnboardingVerification(
                    complete = setupState.isComplete(step),
                    unsupported = step == OnboardingSetupStep.CallScreening && !setupState.screenerSupported,
                    accent = presentation.accent,
                )
                presentation.instruction?.let { instruction ->
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.onboarding_what_happens_next),
                                style = MaterialTheme.typography.labelLarge,
                                color = CatText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = instruction,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CatSubtext,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingIntroDetails() {
    Text(
        text = stringResource(R.string.onboarding_intro_time),
        style = MaterialTheme.typography.titleSmall,
        color = CatTeal,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(18.dp))
    OnboardingPlainRow(Icons.Default.Security, stringResource(R.string.onboarding_intro_private))
    OnboardingPlainRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.onboarding_intro_guided))
    OnboardingPlainRow(Icons.Default.CheckCircle, stringResource(R.string.onboarding_intro_verified))
}

@Composable
private fun OnboardingVerification(
    complete: Boolean,
    unsupported: Boolean,
    accent: Color,
) {
    val color = if (complete) CatGreen else accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (complete) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text(
                text =
                    when {
                        unsupported -> stringResource(R.string.onboarding_not_supported_verified)
                        complete -> stringResource(R.string.onboarding_android_verified)
                        else -> stringResource(R.string.onboarding_action_needed)
                    },
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
            if (!complete) {
                Text(
                    text = stringResource(R.string.onboarding_return_to_verify),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
            }
        }
    }
}

@Composable
private fun OnboardingReview(setupState: OnboardingSetupState) {
    OnboardingReviewRow(
        stringResource(R.string.onboarding_runtime_title),
        setupState.runtimePermissionsGranted,
    )
    OnboardingReviewRow(
        stringResource(R.string.onboarding_call_screening_title),
        setupState.isComplete(OnboardingSetupStep.CallScreening),
        unsupported = !setupState.screenerSupported,
    )
    OnboardingReviewRow(
        stringResource(R.string.onboarding_notifications_title),
        setupState.notificationsGranted,
    )
    OnboardingReviewRow(
        stringResource(R.string.onboarding_overlay_title),
        setupState.overlayGranted,
    )
    OnboardingReviewRow(
        stringResource(R.string.onboarding_notification_access_title),
        setupState.notificationAccessGranted,
    )
}

@Composable
private fun OnboardingReviewRow(
    label: String,
    complete: Boolean,
    unsupported: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (complete) Icons.Default.Check else Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = if (complete) CatGreen else CatYellow,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = CatText,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                when {
                    unsupported -> stringResource(R.string.onboarding_skipped)
                    complete -> stringResource(R.string.onboarding_ready)
                    else -> stringResource(R.string.onboarding_needed)
                },
            style = MaterialTheme.typography.labelMedium,
            color = if (complete) CatGreen else CatYellow,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun OnboardingPlainRow(
    icon: ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = CatTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = CatText)
    }
}

@Composable
private fun primaryLabel(
    step: OnboardingSetupStep,
    setupState: OnboardingSetupState,
    runtimePermissionsBlocked: Boolean,
): String =
    when {
        step == OnboardingSetupStep.Intro -> {
            stringResource(R.string.onboarding_start_setup)
        }

        step == OnboardingSetupStep.Review && setupState.isReady -> {
            stringResource(R.string.onboarding_finish_setup)
        }

        step == OnboardingSetupStep.Review -> {
            stringResource(R.string.onboarding_fix_missing)
        }

        setupState.isComplete(step) -> {
            stringResource(R.string.onboarding_continue)
        }

        step == OnboardingSetupStep.RuntimePermissions && runtimePermissionsBlocked -> {
            stringResource(R.string.onboarding_open_app_permissions)
        }

        step == OnboardingSetupStep.RuntimePermissions -> {
            stringResource(R.string.onboarding_allow_permissions)
        }

        step == OnboardingSetupStep.CallScreening -> {
            stringResource(R.string.onboarding_choose_callshield)
        }

        step == OnboardingSetupStep.Notifications -> {
            stringResource(R.string.onboarding_enable_notifications)
        }

        step == OnboardingSetupStep.Overlay -> {
            stringResource(R.string.onboarding_open_display_settings)
        }

        step == OnboardingSetupStep.NotificationAccess -> {
            stringResource(R.string.onboarding_open_notification_access)
        }

        else -> {
            stringResource(R.string.onboarding_continue)
        }
    }

private fun primaryIcon(
    step: OnboardingSetupStep,
    setupState: OnboardingSetupState,
): ImageVector =
    when {
        step == OnboardingSetupStep.Review && setupState.isReady -> Icons.Default.Check
        setupState.isComplete(step) -> Icons.AutoMirrored.Filled.ArrowForward
        else -> Icons.AutoMirrored.Filled.OpenInNew
    }

internal fun primaryTag(step: OnboardingSetupStep): String =
    when (step) {
        OnboardingSetupStep.RuntimePermissions -> ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG
        OnboardingSetupStep.CallScreening -> ONBOARDING_SCREENER_BUTTON_TAG
        OnboardingSetupStep.Notifications -> ONBOARDING_NOTIFICATIONS_BUTTON_TAG
        OnboardingSetupStep.Overlay -> ONBOARDING_OVERLAY_BUTTON_TAG
        OnboardingSetupStep.NotificationAccess -> ONBOARDING_NOTIFICATION_ACCESS_BUTTON_TAG
        OnboardingSetupStep.Review -> ONBOARDING_FINISH_BUTTON_TAG
        OnboardingSetupStep.Intro -> ONBOARDING_PRIMARY_ACTION_TAG
    }
