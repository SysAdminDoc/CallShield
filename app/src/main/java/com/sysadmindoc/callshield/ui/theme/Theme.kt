package com.sysadmindoc.callshield.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─── AMOLED Black + Catppuccin Mocha ───────────────────────────────
val Black = Color(0xFF000000)
val Surface = Color(0xFF080808)
val SurfaceVariant = Color(0xFF111113)
val SurfaceBright = Color(0xFF1A1A1E)
val SurfaceElevated = Color(0xFF1E1E22)

// Catppuccin Mocha accent palette
val CatGreen = Color(0xFFA6E3A1)
val CatRed = Color(0xFFF38BA8)
val CatBlue = Color(0xFF89B4FA)
val CatYellow = Color(0xFFF9E2AF)
val CatMauve = Color(0xFFCBA6F7)
val CatPeach = Color(0xFFFAB387)
val CatTeal = Color(0xFF94E2D5)
val CatLavender = Color(0xFFB4BEFE)

// Text hierarchy
val CatText = Color(0xFFCDD6F4)
val CatSubtext = Color(0xFF9399B2)
val CatOverlay = Color(0xFF585B70)
val CatMuted = Color(0xFF45475A)

// ─── Premium surface borders ───────────────────────────────────────
val CardBorder = Color.White.copy(alpha = 0.06f)
val CardBorderAccent = Color.White.copy(alpha = 0.09f)
val DividerColor = Color.White.copy(alpha = 0.04f)

// Shared shape rhythm. Text-bearing backdrops intentionally stay rectangular
// with modest corners; full-pill shapes are banned by the product rules.
val ShapeXs = 4.dp
val ShapeSm = 6.dp
val ShapeMd = 8.dp
val ShapeLg = 10.dp
val ShapeXl = 12.dp

// ─── Gradient presets ──────────────────────────────────────────────
val SurfaceGradient = Brush.verticalGradient(
    colors = listOf(SurfaceVariant, Color(0xFF0D0D10))
)
val HeroGradient = Brush.radialGradient(
    colors = listOf(CatGreen.copy(alpha = 0.08f), Color.Transparent),
    radius = 600f
)
val DangerGradient = Brush.radialGradient(
    colors = listOf(CatRed.copy(alpha = 0.06f), Color.Transparent),
    radius = 400f
)

private val DarkColorScheme = darkColorScheme(
    primary = CatGreen,
    onPrimary = Black,
    primaryContainer = Color(0xFF162016),
    secondary = CatBlue,
    onSecondary = Black,
    secondaryContainer = Color(0xFF141C2A),
    tertiary = CatMauve,
    error = CatRed,
    onError = Black,
    background = Black,
    onBackground = CatText,
    surface = Surface,
    onSurface = CatText,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = CatSubtext,
    outline = CatOverlay,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF0F0F12),
    surfaceContainerHigh = SurfaceVariant,
    surfaceContainerHighest = SurfaceBright
)

// ─── Custom Typography ─────────────────────────────────────────────
// Tighter headlines, wider labels — the hallmark of premium type
private val PremiumTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
        lineHeight = 34.sp,
        color = CatText
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = 0.sp,
        lineHeight = 30.sp,
        color = CatText
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
        lineHeight = 26.sp,
        color = CatText
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
        lineHeight = 24.sp,
        color = CatText
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        lineHeight = 21.sp,
        color = CatText
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        lineHeight = 18.sp,
        color = CatText
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        lineHeight = 22.sp,
        color = CatText
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        lineHeight = 19.sp,
        color = CatText
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
        lineHeight = 17.sp,
        color = CatSubtext
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        lineHeight = 18.sp,
        color = CatSubtext
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
        lineHeight = 15.sp,
        color = CatSubtext
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.sp,
        lineHeight = 14.sp,
        color = CatOverlay
    )
)

@Composable
fun CallShieldTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = Black.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Black.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = PremiumTypography,
        content = content
    )
}

// ─── Premium Card ──────────────────────────────────────────────────
// The default card primitive — subtle border + refined surface
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentColor: Color? = null,
    cornerRadius: Dp = ShapeXl,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (accentColor != null) {
        BorderStroke(1.dp, accentColor.copy(alpha = 0.12f))
    } else {
        BorderStroke(1.dp, CardBorder)
    }
    val shape = RoundedCornerShape(cornerRadius)
    val colors = CardDefaults.cardColors(containerColor = SurfaceVariant)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = colors,
            shape = shape,
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            colors = colors,
            shape = shape,
            border = border,
            content = content
        )
    }
}

// ─── Section Header ────────────────────────────────────────────────
// Uppercase label with accent bar — used in settings, stats, etc.
@Composable
fun SectionHeader(title: String, color: Color = CatOverlay) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .background(color, RoundedCornerShape(ShapeXs))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
    }
}

/**
 * Status badge. Despite the legacy name, this MUST NOT render with a pill /
 * oval / fully-rounded backdrop — that visual reads as amateur and is banned
 * by the project's design rules. Differentiation is via colour, border, and
 * font weight, not shape. Corner radius is a subtle 6.dp so the element
 * still feels distinct from a flat rectangle without crossing into pill
 * territory.
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    containerAlpha: Float = 0.12f,
    borderAlpha: Float = 0.18f,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(ShapeSm),
        color = color.copy(alpha = containerAlpha),
        border = BorderStroke(1.dp, color.copy(alpha = borderAlpha))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            style = textStyle,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PremiumIconTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    contentDescription: String? = null,
    containerAlpha: Float = 0.12f,
    borderAlpha: Float = 0.14f,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(ShapeXl),
        color = color.copy(alpha = containerAlpha),
        border = BorderStroke(1.dp, color.copy(alpha = borderAlpha))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun PremiumActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    outlined: Boolean = false,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(ShapeXl)
    val foregroundColor = when {
        !enabled -> CatOverlay
        outlined -> color
        else -> Black
    }
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = foregroundColor
            )
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = foregroundColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = foregroundColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.42f else 0.16f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = color,
                disabledContentColor = CatOverlay,
                containerColor = color.copy(alpha = if (enabled) 0.05f else 0.02f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = modifier.heightIn(min = 46.dp),
            content = content
        )
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.30f else 0.10f)),
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Black,
                disabledContainerColor = color.copy(alpha = 0.18f),
                disabledContentColor = CatOverlay
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = modifier.heightIn(min = 46.dp),
            content = content
        )
    }
}

@Composable
fun PremiumCompactButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val foregroundColor = if (enabled) color else CatOverlay
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(ShapeLg),
        border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.28f else 0.12f)),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = foregroundColor,
            disabledContentColor = CatOverlay,
            containerColor = color.copy(alpha = if (enabled) 0.04f else 0.02f)
        )
    ) {
        Icon(icon, contentDescription = label, tint = foregroundColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = foregroundColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PremiumStateCard(
    icon: ImageVector,
    title: String,
    body: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    PremiumCard(accentColor = accentColor, modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PremiumIconTile(
                icon = icon,
                color = accentColor,
                size = 58.dp,
                iconSize = 30.dp
            )
            Text(
                title,
                color = CatText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                body,
                color = CatSubtext,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                PremiumActionButton(
                    label = actionLabel,
                    icon = Icons.Default.Refresh,
                    color = accentColor,
                    onClick = onAction,
                    outlined = true
                )
            }
        }
    }
}

// ─── Accent Glow Modifier ─────────────────────────────────────────
// Draws a soft radial glow behind the element
fun Modifier.accentGlow(color: Color, radius: Float = 500f, alpha: Float = 0.08f): Modifier =
    this.drawBehind {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = Offset(size.width / 2, size.height / 2)
        )
    }

// ─── Gradient Divider ──────────────────────────────────────────────
@Composable
fun GradientDivider(modifier: Modifier = Modifier, color: Color = CatOverlay) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.15f),
                        color.copy(alpha = 0.15f),
                        Color.Transparent
                    )
                )
            )
    )
}

// ─── Shimmer Loading Skeleton ──────────────────────────────────────
// Animated placeholder for loading states — premium apps never show raw spinners
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    val shimmerAnim = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by shimmerAnim.animateFloat(
        initialValue = -1f, targetValue = 2f, label = "translate",
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            CatMuted.copy(alpha = 0.08f),
            CatMuted.copy(alpha = 0.18f),
            CatMuted.copy(alpha = 0.08f),
        ),
        start = Offset(translateAnim * 300f, 0f),
        end = Offset(translateAnim * 300f + 300f, 0f)
    )
    Box(
        modifier = modifier
            .background(shimmerBrush, RoundedCornerShape(cornerRadius))
    )
}

// Skeleton card that mimics a list item while loading
@Composable
fun SkeletonListItem(modifier: Modifier = Modifier) {
    PremiumCard(modifier = modifier, cornerRadius = ShapeXl) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ShimmerBox(modifier = Modifier.size(36.dp), cornerRadius = ShapeLg)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp))
                Spacer(Modifier.height(6.dp))
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(10.dp))
            }
        }
    }
}

// ─── Haptic Feedback ───────────────────────────────────────────────
// Unified haptic feedback for key interactions
@Suppress("DEPRECATION")
fun hapticTick(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(15, 80))
        } else {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(15, 80))
        }
    } catch (_: Exception) {}
}

@Suppress("DEPRECATION")
fun hapticConfirm(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(40, 150))
        } else {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(40, 150))
        }
    } catch (_: Exception) {}
}
