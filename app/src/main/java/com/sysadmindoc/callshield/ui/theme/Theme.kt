@file:Suppress("MagicNumber", "TooManyFunctions", "ktlint:standard:no-wildcard-imports")

package com.sysadmindoc.callshield.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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

enum class AppThemeMode(
    val storageValue: String,
) {
    System("system"),
    Light("light"),
    Graphite("graphite"),
    Amoled("amoled"),
    ;

    companion object {
        fun fromStorage(value: String?): AppThemeMode = entries.firstOrNull { it.storageValue == value } ?: Amoled
    }
}

@Immutable
data class CallShieldPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceBright: Color,
    val surfaceElevated: Color,
    val primary: Color,
    val onPrimary: Color,
    val error: Color,
    val blue: Color,
    val warning: Color,
    val mauve: Color,
    val peach: Color,
    val teal: Color,
    val lavender: Color,
    val text: Color,
    val subtext: Color,
    val overlay: Color,
    val muted: Color,
    val isLight: Boolean,
)

private val AmoledPalette =
    CallShieldPalette(
        background = Color(0xFF000000),
        surface = Color(0xFF070807),
        surfaceVariant = Color(0xFF0D0F0E),
        surfaceBright = Color(0xFF151715),
        surfaceElevated = Color(0xFF1A1C1A),
        primary = Color(0xFFA6E3A1),
        onPrimary = Color(0xFF071108),
        error = Color(0xFFF38BA8),
        blue = Color(0xFF89B4FA),
        warning = Color(0xFFF9E2AF),
        mauve = Color(0xFFCBA6F7),
        peach = Color(0xFFFAB387),
        teal = Color(0xFF94E2D5),
        lavender = Color(0xFFB4BEFE),
        text = Color(0xFFF2F3F0),
        subtext = Color(0xFFA8ADA8),
        overlay = Color(0xFF747A75),
        muted = Color(0xFF2B2F2C),
        isLight = false,
    )

private val GraphitePalette =
    CallShieldPalette(
        background = Color(0xFF0F1216),
        surface = Color(0xFF14181D),
        surfaceVariant = Color(0xFF1A2027),
        surfaceBright = Color(0xFF222A33),
        surfaceElevated = Color(0xFF29333E),
        primary = Color(0xFF8FD3B2),
        onPrimary = Color(0xFF082117),
        error = Color(0xFFFF9AAE),
        blue = Color(0xFF8AB8FF),
        warning = Color(0xFFE9CC81),
        mauve = Color(0xFFC7B1FF),
        peach = Color(0xFFF0AD7F),
        teal = Color(0xFF82CEC7),
        lavender = Color(0xFFABB9FF),
        text = Color(0xFFF3F6F8),
        subtext = Color(0xFFB1BBC5),
        overlay = Color(0xFF7E8995),
        muted = Color(0xFF333D48),
        isLight = false,
    )

private val LightPalette =
    CallShieldPalette(
        background = Color(0xFFF5F6F7),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEEF1F3),
        surfaceBright = Color(0xFFE5E9EC),
        surfaceElevated = Color(0xFFFFFFFF),
        primary = Color(0xFF176B53),
        onPrimary = Color(0xFFFFFFFF),
        error = Color(0xFFB32642),
        blue = Color(0xFF285FAE),
        warning = Color(0xFF765900),
        mauve = Color(0xFF6D4EA1),
        peach = Color(0xFF98552D),
        teal = Color(0xFF126B66),
        lavender = Color(0xFF4E5F9E),
        text = Color(0xFF171B1F),
        subtext = Color(0xFF4E5963),
        overlay = Color(0xFF68737D),
        muted = Color(0xFFD6DDE2),
        isLight = true,
    )

private val LocalCallShieldPalette = staticCompositionLocalOf { AmoledPalette }

internal fun paletteFor(
    themeMode: AppThemeMode,
    systemDark: Boolean,
): CallShieldPalette =
    when (themeMode) {
        AppThemeMode.System -> if (systemDark) GraphitePalette else LightPalette
        AppThemeMode.Light -> LightPalette
        AppThemeMode.Graphite -> GraphitePalette
        AppThemeMode.Amoled -> AmoledPalette
    }

val Black: Color
    @Composable get() = LocalCallShieldPalette.current.background
val Surface: Color
    @Composable get() = LocalCallShieldPalette.current.surface
val SurfaceVariant: Color
    @Composable get() = LocalCallShieldPalette.current.surfaceVariant
val SurfaceBright: Color
    @Composable get() = LocalCallShieldPalette.current.surfaceBright
val SurfaceElevated: Color
    @Composable get() = LocalCallShieldPalette.current.surfaceElevated
val CatGreen: Color
    @Composable get() = LocalCallShieldPalette.current.primary
val CatRed: Color
    @Composable get() = LocalCallShieldPalette.current.error
val CatBlue: Color
    @Composable get() = LocalCallShieldPalette.current.blue
val CatYellow: Color
    @Composable get() = LocalCallShieldPalette.current.warning
val CatMauve: Color
    @Composable get() = LocalCallShieldPalette.current.mauve
val CatPeach: Color
    @Composable get() = LocalCallShieldPalette.current.peach
val CatTeal: Color
    @Composable get() = LocalCallShieldPalette.current.teal
val CatLavender: Color
    @Composable get() = LocalCallShieldPalette.current.lavender
val CatText: Color
    @Composable get() = LocalCallShieldPalette.current.text
val CatSubtext: Color
    @Composable get() = LocalCallShieldPalette.current.subtext
val CatOverlay: Color
    @Composable get() = LocalCallShieldPalette.current.overlay
val CatMuted: Color
    @Composable get() = LocalCallShieldPalette.current.muted
val CardBorder: Color
    @Composable get() = LocalCallShieldPalette.current.overlay.copy(alpha = 0.16f)
val CardBorderAccent: Color
    @Composable get() = LocalCallShieldPalette.current.overlay.copy(alpha = 0.24f)
val DividerColor: Color
    @Composable get() = LocalCallShieldPalette.current.overlay.copy(alpha = 0.16f)

// Shared shape rhythm. Text-bearing backdrops intentionally stay rectangular
// with modest corners; full-pill shapes are banned by the product rules.
val ShapeXs = 4.dp
val ShapeSm = 6.dp
val ShapeMd = 8.dp
val ShapeLg = 8.dp
val ShapeXl = 10.dp

// ─── Gradient presets ──────────────────────────────────────────────
val SurfaceGradient: Brush
    @Composable get() = Brush.verticalGradient(listOf(SurfaceVariant, Surface))
val HeroGradient: Brush
    @Composable get() = Brush.radialGradient(listOf(CatGreen.copy(alpha = 0.08f), Color.Transparent), radius = 600f)
val DangerGradient: Brush
    @Composable get() = Brush.radialGradient(listOf(CatRed.copy(alpha = 0.06f), Color.Transparent), radius = 400f)

@Suppress("LongMethod")
private fun colorScheme(palette: CallShieldPalette): ColorScheme {
    val common: (Boolean) -> ColorScheme = { light ->
        if (light) {
            lightColorScheme(
                primary = palette.primary,
                onPrimary = palette.onPrimary,
                primaryContainer = palette.surfaceVariant,
                onPrimaryContainer = palette.primary,
                secondary = palette.blue,
                onSecondary = palette.onPrimary,
                secondaryContainer = palette.surfaceVariant,
                onSecondaryContainer = palette.blue,
                tertiary = palette.mauve,
                error = palette.error,
                onError = palette.onPrimary,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceVariant,
                onSurfaceVariant = palette.subtext,
                outline = palette.overlay,
                outlineVariant = palette.muted,
                surfaceContainerLowest = palette.background,
                surfaceContainerLow = palette.surface,
                surfaceContainer = palette.surfaceVariant,
                surfaceContainerHigh = palette.surfaceBright,
                surfaceContainerHighest = palette.surfaceElevated,
            )
        } else {
            darkColorScheme(
                primary = palette.primary,
                onPrimary = palette.onPrimary,
                primaryContainer = palette.surfaceVariant,
                onPrimaryContainer = palette.primary,
                secondary = palette.blue,
                onSecondary = palette.onPrimary,
                secondaryContainer = palette.surfaceVariant,
                onSecondaryContainer = palette.blue,
                tertiary = palette.mauve,
                error = palette.error,
                onError = palette.onPrimary,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceVariant,
                onSurfaceVariant = palette.subtext,
                outline = palette.overlay,
                outlineVariant = palette.muted,
                surfaceContainerLowest = palette.background,
                surfaceContainerLow = palette.surface,
                surfaceContainer = palette.surfaceVariant,
                surfaceContainerHigh = palette.surfaceBright,
                surfaceContainerHighest = palette.surfaceElevated,
            )
        }
    }
    return common(palette.isLight)
}

// ─── Custom Typography ─────────────────────────────────────────────
// Tighter headlines, wider labels — the hallmark of premium type
private val CallShieldTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                letterSpacing = 0.sp,
                lineHeight = 32.sp,
            ),
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                letterSpacing = 0.sp,
                lineHeight = 28.sp,
            ),
        headlineSmall =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                letterSpacing = 0.sp,
                lineHeight = 24.sp,
            ),
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 0.sp,
                lineHeight = 24.sp,
            ),
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.sp,
                lineHeight = 21.sp,
            ),
        titleSmall =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                letterSpacing = 0.sp,
                lineHeight = 19.sp,
            ),
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                letterSpacing = 0.sp,
                lineHeight = 21.sp,
            ),
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                letterSpacing = 0.sp,
                lineHeight = 18.sp,
            ),
        bodySmall =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                letterSpacing = 0.sp,
                lineHeight = 17.sp,
            ),
        labelLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 0.sp,
                lineHeight = 15.sp,
            ),
    )

@Composable
@Suppress("FunctionNaming")
fun CallShieldTheme(
    themeMode: AppThemeMode = AppThemeMode.Amoled,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = paletteFor(themeMode, systemDark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = palette.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme(palette),
        typography = CallShieldTypography,
    ) {
        CompositionLocalProvider(LocalCallShieldPalette provides palette, content = content)
    }
}

// Shared quiet surface. Hierarchy comes from tone and spacing, not stacked
// outlines, gradients, or decorative elevation.
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentColor: Color? = null,
    cornerRadius: Dp = ShapeXl,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = colors,
            shape = shape,
            elevation = elevation,
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            colors = colors,
            shape = shape,
            elevation = elevation,
            content = content,
        )
    }
}

// Compact section label. It should orient, not compete with page content.
@Composable
fun SectionHeader(
    title: String,
    color: Color = CatOverlay,
) {
    Text(
        title,
        modifier = Modifier.padding(vertical = 1.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (color == CatOverlay) CatSubtext else color,
        fontWeight = FontWeight.SemiBold,
    )
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
    horizontalPadding: Dp = 0.dp,
    verticalPadding: Dp = 2.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Row(
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .background(color, RoundedCornerShape(ShapeXs)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = textStyle,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PremiumIconTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
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
    val shape = RoundedCornerShape(ShapeLg)
    val foregroundColor =
        when {
            !enabled -> CatOverlay
            outlined -> color
            else -> MaterialTheme.colorScheme.onPrimary
        }
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = foregroundColor,
            )
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = foregroundColor,
                modifier = Modifier.size(18.dp),
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
            textAlign = TextAlign.Center,
        )
    }

    if (outlined) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = color,
                    disabledContentColor = CatOverlay,
                ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = modifier.heightIn(min = 44.dp),
            content = content,
        )
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = color,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = color.copy(alpha = 0.18f),
                    disabledContentColor = CatOverlay,
                ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = modifier.heightIn(min = 44.dp),
            content = content,
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
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(ShapeLg),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = foregroundColor,
                disabledContentColor = CatOverlay,
            ),
    ) {
        Icon(icon, contentDescription = label, tint = foregroundColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = foregroundColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PremiumIconTile(
                icon = icon,
                color = accentColor,
                size = 44.dp,
                iconSize = 24.dp,
            )
            Text(
                title,
                color = CatText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                color = CatSubtext,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                PremiumActionButton(
                    label = actionLabel,
                    icon = Icons.Default.Refresh,
                    color = accentColor,
                    onClick = onAction,
                    outlined = true,
                )
            }
        }
    }
}

// ─── Accent Glow Modifier ─────────────────────────────────────────
// Draws a soft radial glow behind the element
fun Modifier.accentGlow(
    color: Color,
    radius: Float = 500f,
    alpha: Float = 0.08f,
): Modifier =
    this.drawBehind {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = Offset(size.width / 2, size.height / 2),
        )
    }

// Legacy name retained for call sites; the visual is intentionally a plain
// hairline so groups do not accumulate decorative gradients.
@Composable
fun GradientDivider(
    modifier: Modifier = Modifier,
    color: Color = CatOverlay,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (color == CatOverlay) DividerColor else color.copy(alpha = 0.16f)),
    )
}

// ─── Shimmer Loading Skeleton ──────────────────────────────────────
// Animated placeholder for loading states — premium apps never show raw spinners
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val shimmerAnim = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by shimmerAnim.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        label = "translate",
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
    )
    val shimmerBrush =
        Brush.linearGradient(
            colors =
                listOf(
                    CatMuted.copy(alpha = 0.08f),
                    CatMuted.copy(alpha = 0.18f),
                    CatMuted.copy(alpha = 0.08f),
                ),
            start = Offset(translateAnim * 300f, 0f),
            end = Offset(translateAnim * 300f + 300f, 0f),
        )
    Box(
        modifier =
            modifier
                .background(shimmerBrush, RoundedCornerShape(cornerRadius)),
    )
}

// Skeleton card that mimics a list item while loading
@Composable
fun SkeletonListItem(modifier: Modifier = Modifier) {
    PremiumCard(modifier = modifier, cornerRadius = ShapeXl) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
    } catch (_: Exception) {
    }
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
    } catch (_: Exception) {
    }
}
