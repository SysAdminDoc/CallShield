package com.sysadmindoc.callshield.ui.screens.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color,
)

internal const val ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG = "onboarding_core_permissions_button"
internal const val ONBOARDING_NOTIFICATIONS_BUTTON_TAG = "onboarding_notifications_button"
internal const val ONBOARDING_OVERLAY_BUTTON_TAG = "onboarding_overlay_button"
internal const val ONBOARDING_SCREENER_BUTTON_TAG = "onboarding_screener_button"

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val roleManager =
        remember {
            context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        }
    var permsGranted by remember(context) { mutableStateOf(CallShieldPermissions.hasCorePermissions(context)) }
    var notificationsGranted by remember(context) { mutableStateOf(CallShieldPermissions.hasNotificationPermission(context)) }
    var overlayGranted by remember(context) { mutableStateOf(CallShieldPermissions.canDrawOverlays(context)) }
    var screenerGranted by remember(roleManager) { mutableStateOf(CallShieldPermissions.hasCallScreeningRole(roleManager)) }
    val screenerSupported = remember(roleManager) { roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true }
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Once the user has permanently denied ("Don't allow"), launch() returns
    // instantly denied with no system UI, so the Grant button visibly does
    // nothing. Detect that and route to app settings, matching the fallback
    // the notifications path already has.
    var corePermissionsBlocked by rememberSaveable { mutableStateOf(false) }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permsGranted = CallShieldPermissions.hasCorePermissions(context)
            val activity = context as? android.app.Activity
            corePermissionsBlocked =
                !permsGranted &&
                result.isNotEmpty() &&
                result.none { it.value } &&
                activity != null &&
                result.keys.none(activity::shouldShowRequestPermissionRationale)
        }

    // Notification permission (Android 13+) — separate launcher since it's a single permission
    val notifPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
        }

    val screenerErrorMessage = stringResource(R.string.onboarding_screener_error)

    val screeningLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
        }

    DisposableEffect(lifecycleOwner, context, roleManager) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    permsGranted = CallShieldPermissions.hasCorePermissions(context)
                    notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
                    overlayGranted = CallShieldPermissions.canDrawOverlays(context)
                    screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingScreenContent(
        permsGranted = permsGranted,
        notificationsGranted = notificationsGranted,
        overlayGranted = overlayGranted,
        screenerGranted = screenerGranted,
        screenerSupported = screenerSupported,
        snackbarHostState = snackbarHostState,
        onRequestCorePermissions = {
            if (corePermissionsBlocked) {
                // The system dialog will never appear again — send the user
                // where they can actually grant it.
                context.startActivitySafely(
                    android.content.Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null),
                    ),
                )
            } else {
                permLauncher.launch(CallShieldPermissions.corePermissions.toTypedArray())
            }
        },
        onRequestNotifications = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // POST_NOTIFICATIONS doesn't exist below API 33 (minSdk 29) —
                // the launcher would auto-deny and the button would silently do
                // nothing. Open the OS notification settings instead, matching
                // the Settings screen's behavior.
                context.startActivitySafely(
                    android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    },
                )
            }
        },
        onRequestOverlay = {
            context.startActivitySafely(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        onRequestScreener = {
            if (roleManager != null) {
                try {
                    screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                } catch (_: Exception) {
                    scope.launch {
                        snackbarHostState.showSnackbar(screenerErrorMessage)
                    }
                }
            }
        },
        onComplete = onComplete,
    )
}

@Composable
internal fun OnboardingScreenContent(
    permsGranted: Boolean,
    notificationsGranted: Boolean,
    overlayGranted: Boolean,
    screenerGranted: Boolean,
    screenerSupported: Boolean,
    onRequestCorePermissions: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestScreener: () -> Unit,
    onComplete: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    // The call-screening role only counts toward "required" on devices that
    // actually have it — otherwise the badge is permanently stuck at 1/2 and the
    // finish CTA never becomes "Finish setup" on ROMs without ROLE_CALL_SCREENING.
    val requiredReady = listOf(permsGranted, !screenerSupported || screenerGranted).count { it }
    val pages =
        listOf(
            OnboardingPage(
                Icons.Default.Shield,
                stringResource(R.string.onboarding_welcome_title),
                stringResource(R.string.onboarding_welcome_subtitle),
                CatGreen,
            ),
            OnboardingPage(
                Icons.Default.Security,
                stringResource(R.string.onboarding_permissions_title),
                stringResource(R.string.onboarding_permissions_subtitle),
                CatBlue,
            ),
            OnboardingPage(
                Icons.AutoMirrored.Filled.PhoneCallback,
                stringResource(R.string.onboarding_screener_title),
                stringResource(R.string.onboarding_screener_subtitle),
                CatMauve,
            ),
            OnboardingPage(
                Icons.Default.Sync,
                stringResource(R.string.onboarding_sync_title),
                stringResource(R.string.onboarding_sync_subtitle),
                CatPeach,
            ),
        )
    val pageContentDescription =
        stringResource(
            R.string.cd_onboarding_page,
            currentPage + 1,
            pages.size,
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Black)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.onboarding_step, currentPage + 1, pages.size),
                style = MaterialTheme.typography.labelMedium,
                color = CatSubtext,
            )
            StatusPill(
                text = stringResource(R.string.onboarding_progress_required_badge, requiredReady, 2),
                color = if (requiredReady == 2) CatGreen else pages[currentPage].color,
                textStyle = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (currentPage + 1) / pages.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = pages[currentPage].color,
            trackColor = CatMuted.copy(alpha = 0.35f),
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = pageContentDescription
                    },
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(targetState = currentPage, transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            }, label = "onboarding") { page ->
                val p = pages[page]
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (page == 0) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = p.title,
                            modifier = Modifier.size(48.dp),
                        )
                    } else {
                        Icon(
                            p.icon,
                            contentDescription = p.title,
                            tint = p.color,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        p.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = p.color,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CatSubtext,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))

                    when (page) {
                        0 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusPill(
                                    text = stringResource(R.string.onboarding_trust_no_account),
                                    color = CatSubtext,
                                    textStyle = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusPill(
                                    text = stringResource(R.string.onboarding_trust_on_device),
                                    color = CatSubtext,
                                    textStyle = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusPill(
                                    text = stringResource(R.string.onboarding_trust_open_source),
                                    color = CatSubtext,
                                    textStyle = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_private_title),
                                body = stringResource(R.string.onboarding_feature_private_body),
                            )
                            Spacer(Modifier.height(4.dp))
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_local_title),
                                body = stringResource(R.string.onboarding_feature_local_body),
                            )
                            Spacer(Modifier.height(4.dp))
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_updates_title),
                                body = stringResource(R.string.onboarding_feature_updates_body),
                            )
                        }

                        1 -> {
                            PremiumCard(accentColor = CatBlue, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OnboardingChecklistItem(
                                        title = stringResource(R.string.onboarding_grant_permissions),
                                        detail = stringResource(R.string.onboarding_core_permissions_detail),
                                        granted = permsGranted,
                                        accentColor = CatBlue,
                                        badge = stringResource(R.string.onboarding_permissions_required),
                                    )
                                    OnboardingChecklistItem(
                                        title = stringResource(R.string.settings_notifications),
                                        detail = stringResource(R.string.onboarding_notification_detail),
                                        granted = notificationsGranted,
                                        accentColor = CatBlue,
                                        badge = stringResource(R.string.onboarding_permissions_optional),
                                    )
                                    OnboardingChecklistItem(
                                        title = stringResource(R.string.settings_overlay),
                                        detail = stringResource(R.string.onboarding_overlay_detail),
                                        granted = overlayGranted,
                                        accentColor = CatBlue,
                                        badge = stringResource(R.string.onboarding_permissions_optional),
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (!permsGranted) {
                                PremiumActionButton(
                                    label = stringResource(R.string.onboarding_grant_permissions),
                                    icon = Icons.Default.Security,
                                    color = CatBlue,
                                    onClick = {
                                        onRequestCorePermissions()
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag(ONBOARDING_CORE_PERMISSIONS_BUTTON_TAG),
                                )
                            }

                            // Show whenever notifications are off, on all API levels —
                            // below 33, onRequestNotifications() opens OS settings.
                            if (!notificationsGranted) {
                                Spacer(Modifier.height(10.dp))
                                PremiumActionButton(
                                    label = stringResource(R.string.onboarding_enable_notifications),
                                    icon = Icons.Default.Notifications,
                                    color = CatBlue,
                                    onClick = {
                                        onRequestNotifications()
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag(ONBOARDING_NOTIFICATIONS_BUTTON_TAG),
                                    outlined = true,
                                )
                            }

                            if (!overlayGranted) {
                                Spacer(Modifier.height(10.dp))
                                PremiumActionButton(
                                    label = stringResource(R.string.onboarding_enable_overlay),
                                    icon = Icons.Default.Layers,
                                    color = CatBlue,
                                    onClick = {
                                        onRequestOverlay()
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag(ONBOARDING_OVERLAY_BUTTON_TAG),
                                    outlined = true,
                                )
                            }
                        }

                        2 -> {
                            PremiumCard(accentColor = CatMauve, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OnboardingChecklistItem(
                                        title =
                                            if (screenerSupported) {
                                                stringResource(R.string.onboarding_set_screener)
                                            } else {
                                                stringResource(R.string.onboarding_screener_unavailable)
                                            },
                                        detail =
                                            if (screenerSupported) {
                                                if (screenerGranted) {
                                                    stringResource(R.string.onboarding_screener_ready_detail)
                                                } else {
                                                    stringResource(R.string.onboarding_screener_pending_detail)
                                                }
                                            } else {
                                                stringResource(R.string.onboarding_screener_unavailable_detail)
                                            },
                                        granted = screenerGranted,
                                        accentColor = CatMauve,
                                        badge = stringResource(R.string.onboarding_permissions_required),
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (screenerSupported && !screenerGranted) {
                                PremiumActionButton(
                                    label = stringResource(R.string.onboarding_set_screener),
                                    icon = Icons.AutoMirrored.Filled.PhoneCallback,
                                    color = CatMauve,
                                    onClick = {
                                        onRequestScreener()
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag(ONBOARDING_SCREENER_BUTTON_TAG),
                                )
                            } else if (screenerGranted) {
                                OnboardingStatusRow(
                                    label = stringResource(R.string.onboarding_screening_enabled),
                                    granted = true,
                                    color = CatGreen,
                                )
                            }
                        }

                        else -> {
                            PremiumCard(
                                accentColor = if (requiredReady == 2) CatGreen else CatYellow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        if (requiredReady == 2) {
                                            stringResource(R.string.onboarding_finish_ready_title)
                                        } else {
                                            stringResource(R.string.onboarding_finish_later_title)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (requiredReady == 2) CatGreen else CatYellow,
                                    )
                                    Text(
                                        if (requiredReady == 2) {
                                            stringResource(R.string.onboarding_finish_ready_subtitle)
                                        } else {
                                            stringResource(R.string.onboarding_finish_later_subtitle)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CatSubtext,
                                    )

                                    OnboardingStatusRow(
                                        label =
                                            if (permsGranted) {
                                                stringResource(R.string.onboarding_permissions_granted)
                                            } else {
                                                stringResource(R.string.settings_permissions_needed)
                                            },
                                        granted = permsGranted,
                                        color = if (permsGranted) CatGreen else CatYellow,
                                    )
                                    OnboardingStatusRow(
                                        label =
                                            if (screenerGranted) {
                                                stringResource(R.string.onboarding_screening_enabled)
                                            } else {
                                                stringResource(R.string.settings_call_screener_needed)
                                            },
                                        granted = screenerGranted,
                                        color = if (screenerGranted) CatGreen else CatYellow,
                                    )
                                    OnboardingStatusRow(
                                        label =
                                            if (notificationsGranted) {
                                                stringResource(R.string.settings_notifications_enabled)
                                            } else {
                                                stringResource(R.string.settings_notifications_optional)
                                            },
                                        granted = notificationsGranted,
                                        color = if (notificationsGranted) CatGreen else CatOverlay,
                                    )
                                    OnboardingStatusRow(
                                        label =
                                            if (overlayGranted) {
                                                stringResource(R.string.settings_overlay_enabled)
                                            } else {
                                                stringResource(R.string.settings_overlay_optional)
                                            },
                                        granted = overlayGranted,
                                        color = if (overlayGranted) CatGreen else CatOverlay,
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            PremiumCard(accentColor = CatPeach, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        stringResource(R.string.onboarding_sync_card_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CatPeach,
                                    )
                                    OnboardingBulletPoint(stringResource(R.string.onboarding_sync_bundled), CatPeach)
                                    OnboardingBulletPoint(stringResource(R.string.onboarding_sync_refresh), CatPeach)
                                    OnboardingBulletPoint(stringResource(R.string.onboarding_sync_hot), CatPeach)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, p ->
                val isSelected = i == currentPage
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isSelected) 24.dp else 8.dp,
                    animationSpec = spring(dampingRatio = 0.7f),
                    label = "indicator",
                )
                Box(
                    modifier =
                        Modifier
                            .width(indicatorWidth)
                            .height(8.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isSelected) p.color else CatOverlay),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SnackbarHost(hostState = snackbarHostState)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentPage > 0) {
                TextButton(onClick = { currentPage-- }) {
                    Text(stringResource(R.string.onboarding_back), color = CatSubtext)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            PremiumActionButton(
                label =
                    when {
                        currentPage < pages.lastIndex -> stringResource(R.string.onboarding_next)
                        requiredReady == 2 -> stringResource(R.string.onboarding_finish_setup)
                        else -> stringResource(R.string.onboarding_continue_anyway)
                    },
                icon =
                    if (currentPage < pages.lastIndex) {
                        Icons.AutoMirrored.Filled.ArrowForward
                    } else {
                        Icons.Default.Check
                    },
                color = pages[currentPage].color,
                onClick = {
                    if (currentPage < pages.lastIndex) {
                        currentPage++
                    } else {
                        onComplete()
                    }
                },
                modifier = Modifier.height(44.dp),
            )
        }
    }
}

@Composable
private fun OnboardingFeatureCard(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = CatText,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = CatSubtext,
        )
        GradientDivider()
    }
}

@Composable
private fun OnboardingChecklistItem(
    title: String,
    detail: String,
    granted: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    badge: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        PremiumIconTile(
            icon = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            color = if (granted) CatGreen else accentColor,
            size = 34.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            Spacer(Modifier.height(6.dp))
            StatusPill(
                text = badge,
                color = accentColor,
                horizontalPadding = 8.dp,
                verticalPadding = 4.dp,
                textStyle = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun OnboardingBulletPoint(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

@Composable
private fun OnboardingStatusRow(
    label: String,
    granted: Boolean,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconTile(
            icon = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            color = color,
            size = 34.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}
