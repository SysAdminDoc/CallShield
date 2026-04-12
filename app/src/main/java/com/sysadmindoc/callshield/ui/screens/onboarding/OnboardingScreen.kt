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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val lifecycleOwner = LocalLifecycleOwner.current

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
        Spacer(Modifier.weight(1f))

        // Page content
        AnimatedContent(targetState = currentPage, transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
        }, label = "onboarding") { page ->
            val p = pages[page]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    p.icon, contentDescription = p.title, tint = p.color,
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

                // Permission request on page 2
                if (page == 1) {
                    Spacer(Modifier.height(24.dp))
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
                    } else {
                        OnboardingStatusRow(
                            label = stringResource(R.string.onboarding_permissions_granted),
                            granted = true,
                            color = CatGreen
                        )
                    }
                    // Also request notification permission on Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(Modifier.height(10.dp))
                        if (notificationsGranted) {
                            OnboardingStatusRow(
                                label = stringResource(R.string.settings_notifications_enabled),
                                granted = true,
                                color = CatGreen
                            )
                        } else {
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
                    }
                    // Overlay permission for caller ID
                    Spacer(Modifier.height(10.dp))
                    if (overlayGranted) {
                        OnboardingStatusRow(
                            label = stringResource(R.string.settings_overlay_enabled),
                            granted = true,
                            color = CatGreen
                        )
                    } else {
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

                // Call screener button on page 3
                if (page == 2) {
                    Spacer(Modifier.height(24.dp))
                    if (screenerGranted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.cd_screening_enabled), tint = CatGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_screening_enabled), color = CatGreen, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (roleManager != null) {
                                    try {
                                        screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                    } catch (_: Exception) {
                                        // ROLE_CALL_SCREENING unavailable on this device — skip silently
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
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Page indicators
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

        Spacer(Modifier.height(32.dp))

        // Navigation
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
                    if (currentPage < pages.lastIndex) stringResource(R.string.onboarding_next) else stringResource(R.string.onboarding_get_started),
                    color = Black, fontWeight = FontWeight.Bold
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
