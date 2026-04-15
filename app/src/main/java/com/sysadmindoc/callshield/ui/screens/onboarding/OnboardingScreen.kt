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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }
    val roleManager = remember {
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
    val requiredReady = listOf(permsGranted, screenerGranted).count { it }
    val optionalReady = listOf(notificationsGranted, overlayGranted).count { it }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsGranted = CallShieldPermissions.hasCorePermissions(context)
    }

    // Notification permission (Android 13+) — separate launcher since it's a single permission
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
    }

    val pages = listOf(
        OnboardingPage(Icons.Default.Shield, stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_subtitle), CatGreen),
        OnboardingPage(Icons.Default.Security, stringResource(R.string.onboarding_permissions_title), stringResource(R.string.onboarding_permissions_subtitle), CatBlue),
        OnboardingPage(Icons.AutoMirrored.Filled.PhoneCallback, stringResource(R.string.onboarding_screener_title), stringResource(R.string.onboarding_screener_subtitle), CatMauve),
        OnboardingPage(Icons.Default.Sync, stringResource(R.string.onboarding_sync_title), stringResource(R.string.onboarding_sync_subtitle), CatPeach),
    )

    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
    }

    DisposableEffect(lifecycleOwner, context, roleManager) {
        val observer = LifecycleEventObserver { _, event ->
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.onboarding_step, currentPage + 1, pages.size),
            style = MaterialTheme.typography.labelMedium,
            color = CatOverlay
        )
        Spacer(Modifier.height(12.dp))

        PremiumCard(accentColor = pages[currentPage].color, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.onboarding_progress_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.onboarding_progress_core, requiredReady, 2),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatText
                )
                Text(
                    stringResource(R.string.onboarding_progress_optional, optionalReady, 2),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                LinearProgressIndicator(
                    progress = { (requiredReady + optionalReady) / 4f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = pages[currentPage].color,
                    trackColor = CatMuted.copy(alpha = 0.2f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = context.getString(
                        R.string.cd_onboarding_page,
                        currentPage + 1,
                        pages.size
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(targetState = currentPage, transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            }, label = "onboarding") { page ->
                val p = pages[page]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        p.icon,
                        contentDescription = p.title,
                        tint = p.color,
                        modifier = Modifier
                            .size(96.dp)
                            .accentGlow(p.color, 300f, 0.10f)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        p.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = p.color,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        p.subtitle,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = CatSubtext,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))

                    when (page) {
                        0 -> {
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_private_title),
                                body = stringResource(R.string.onboarding_feature_private_body),
                                accentColor = CatGreen
                            )
                            Spacer(Modifier.height(10.dp))
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_local_title),
                                body = stringResource(R.string.onboarding_feature_local_body),
                                accentColor = CatBlue
                            )
                            Spacer(Modifier.height(10.dp))
                            OnboardingFeatureCard(
                                title = stringResource(R.string.onboarding_feature_updates_title),
                                body = stringResource(R.string.onboarding_feature_updates_body),
                                accentColor = CatPeach
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
                                        badge = stringResource(R.string.onboarding_permissions_required)
                                    )
                                    OnboardingChecklistItem(
                                        title = stringResource(R.string.settings_notifications),
                                        detail = stringResource(R.string.onboarding_notification_detail),
                                        granted = notificationsGranted,
                                        accentColor = CatBlue,
                                        badge = stringResource(R.string.onboarding_permissions_optional)
                                    )
                                    OnboardingChecklistItem(
                                        title = stringResource(R.string.settings_overlay),
                                        detail = stringResource(R.string.onboarding_overlay_detail),
                                        granted = overlayGranted,
                                        accentColor = CatBlue,
                                        badge = stringResource(R.string.onboarding_permissions_optional)
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (!permsGranted) {
                                Button(
                                    onClick = {
                                        permLauncher.launch(CallShieldPermissions.corePermissions.toTypedArray())
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CatBlue),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Security, null, tint = Black)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.onboarding_grant_permissions), color = Black, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Notifications, null, tint = CatBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.onboarding_enable_notifications), color = CatBlue, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            if (!overlayGranted) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Layers, null, tint = CatBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.onboarding_enable_overlay), color = CatBlue, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        2 -> {
                            PremiumCard(accentColor = CatMauve, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OnboardingChecklistItem(
                                        title = if (screenerSupported) {
                                            stringResource(R.string.onboarding_set_screener)
                                        } else {
                                            stringResource(R.string.onboarding_screener_unavailable)
                                        },
                                        detail = if (screenerSupported) {
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
                                        badge = stringResource(R.string.onboarding_permissions_required)
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (screenerSupported && !screenerGranted) {
                                Button(
                                    onClick = {
                                        if (roleManager != null) {
                                            try {
                                                screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                            } catch (_: Exception) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.onboarding_screener_error))
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CatMauve),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.PhoneCallback, null, tint = Black)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.onboarding_set_screener), color = Black, fontWeight = FontWeight.Bold)
                                }
                            } else if (screenerGranted) {
                                OnboardingStatusRow(
                                    label = stringResource(R.string.onboarding_screening_enabled),
                                    granted = true,
                                    color = CatGreen
                                )
                            }
                        }

                        else -> {
                            PremiumCard(
                                accentColor = if (requiredReady == 2) CatGreen else CatYellow,
                                modifier = Modifier.fillMaxWidth()
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
                                        color = if (requiredReady == 2) CatGreen else CatYellow
                                    )
                                    Text(
                                        if (requiredReady == 2) {
                                            stringResource(R.string.onboarding_finish_ready_subtitle)
                                        } else {
                                            stringResource(R.string.onboarding_finish_later_subtitle)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CatSubtext
                                    )

                                    OnboardingStatusRow(
                                        label = if (permsGranted) {
                                            stringResource(R.string.onboarding_permissions_granted)
                                        } else {
                                            stringResource(R.string.settings_permissions_needed)
                                        },
                                        granted = permsGranted,
                                        color = if (permsGranted) CatGreen else CatYellow
                                    )
                                    OnboardingStatusRow(
                                        label = if (screenerGranted) {
                                            stringResource(R.string.onboarding_screening_enabled)
                                        } else {
                                            stringResource(R.string.settings_call_screener_needed)
                                        },
                                        granted = screenerGranted,
                                        color = if (screenerGranted) CatGreen else CatYellow
                                    )
                                    OnboardingStatusRow(
                                        label = if (notificationsGranted) {
                                            stringResource(R.string.settings_notifications_enabled)
                                        } else {
                                            stringResource(R.string.settings_notifications_optional)
                                        },
                                        granted = notificationsGranted,
                                        color = if (notificationsGranted) CatGreen else CatOverlay
                                    )
                                    OnboardingStatusRow(
                                        label = if (overlayGranted) {
                                            stringResource(R.string.settings_overlay_enabled)
                                        } else {
                                            stringResource(R.string.settings_overlay_needed)
                                        },
                                        granted = overlayGranted,
                                        color = if (overlayGranted) CatGreen else CatOverlay
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
                                        color = CatPeach
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

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, p ->
                val isSelected = i == currentPage
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isSelected) 24.dp else 8.dp,
                    animationSpec = spring(dampingRatio = 0.7f),
                    label = "indicator"
                )
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(8.dp)
                        .clip(if (isSelected) RoundedCornerShape(4.dp) else CircleShape)
                        .background(if (isSelected) p.color else CatOverlay)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SnackbarHost(hostState = snackbarHostState)
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (currentPage > 0) {
                TextButton(onClick = { currentPage-- }) {
                    Text(stringResource(R.string.onboarding_back), color = CatSubtext)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            Button(
                onClick = {
                    if (currentPage < pages.lastIndex) currentPage++
                    else onComplete()
                },
                colors = ButtonDefaults.buttonColors(containerColor = pages[currentPage].color),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, pages[currentPage].color.copy(alpha = 0.3f)),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    when {
                        currentPage < pages.lastIndex -> stringResource(R.string.onboarding_next)
                        requiredReady == 2 -> stringResource(R.string.onboarding_finish_setup)
                        else -> stringResource(R.string.onboarding_continue_anyway)
                    },
                    color = Black,
                    fontWeight = FontWeight.Bold
                )
                if (currentPage < pages.lastIndex) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.cd_next_page), tint = Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeatureCard(title: String, body: String, accentColor: androidx.compose.ui.graphics.Color) {
    PremiumCard(accentColor = accentColor, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
        }
    }
}

@Composable
private fun OnboardingChecklistItem(
    title: String,
    detail: String,
    granted: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    badge: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) CatGreen else accentColor,
            modifier = Modifier.padding(top = 2.dp).size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            Spacer(Modifier.height(6.dp))
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }
    }
}

@Composable
private fun OnboardingBulletPoint(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

@Composable
private fun OnboardingStatusRow(label: String, granted: Boolean, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}
