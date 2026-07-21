package com.sysadmindoc.callshield.permissions

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Coarse risk that an aggressive OEM battery manager (MIUI/HyperOS autostart,
 * Samsung, ColorOS) will silently kill CallShield's background work — stopping
 * WorkManager sync and unbinding the RCS notification listener — with no
 * warning to the user.
 */
enum class BackgroundExecutionRisk {
    /** Nothing to warn about: not background-restricted and battery-exempt (or below the API where it matters). */
    Ok,

    /**
     * The app is not exempt from battery optimizations. Doze/App-Standby can
     * defer sync and the OS may reclaim the listener under memory pressure.
     */
    NotBatteryExempt,

    /**
     * The strongest signal: `ActivityManager.isBackgroundRestricted()` is true,
     * so the OS will actively prevent background execution. Overrides the
     * battery-exempt hint when both apply.
     */
    BackgroundRestricted,
}

/**
 * On-device, AOSP-portable detection of the background-execution risk plus the
 * intents that route the user to the fix. No cloud, no vendor SDK — only the
 * public [ActivityManager] / [PowerManager] signals, with an optional
 * best-effort MIUI autostart probe.
 *
 * UI display is intentionally left to the caller (owned by a separate surface);
 * this object exposes only the pure classification + intents so it can be
 * unit-tested off-device.
 */
object BackgroundExecutionStatus {
    /**
     * Pure classifier: given the two OS signals, decide the risk. Split from
     * the Android reads so it can be exhaustively unit-tested.
     *
     * @param backgroundRestricted result of `ActivityManager.isBackgroundRestricted()`
     * @param ignoringBatteryOptimizations result of `PowerManager.isIgnoringBatteryOptimizations(pkg)`
     * @param restrictionApiSupported false below API 28, where `isBackgroundRestricted` is unavailable
     */
    fun classify(
        backgroundRestricted: Boolean,
        ignoringBatteryOptimizations: Boolean,
        restrictionApiSupported: Boolean = true,
    ): BackgroundExecutionRisk =
        when {
            restrictionApiSupported && backgroundRestricted -> BackgroundExecutionRisk.BackgroundRestricted
            !ignoringBatteryOptimizations -> BackgroundExecutionRisk.NotBatteryExempt
            else -> BackgroundExecutionRisk.Ok
        }

    /** Live evaluation against the current device state. */
    fun evaluate(context: Context): BackgroundExecutionRisk {
        val am = ContextCompat.getSystemService(context, ActivityManager::class.java)
        val pm = ContextCompat.getSystemService(context, PowerManager::class.java)
        val restrictionApiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val restricted =
            restrictionApiSupported && (am?.isBackgroundRestricted ?: false)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        return classify(
            backgroundRestricted = restricted,
            ignoringBatteryOptimizations = exempt,
            restrictionApiSupported = restrictionApiSupported,
        )
    }

    /** True when a warning should be surfaced to the user. */
    fun isAtRisk(context: Context): Boolean = evaluate(context) != BackgroundExecutionRisk.Ok

    /**
     * Intent that opens the per-app battery-optimization/settings screen so the
     * user can grant an exemption. Falls back to the app details settings page
     * on devices/OS levels that don't expose the ignore-optimizations screen.
     */
    fun batteryExemptionSettingsIntent(context: Context): Intent {
        val direct =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        return direct
    }

    /**
     * Best-effort probe for whether this is a Xiaomi/MIUI/HyperOS device, whose
     * autostart manager is the most common silent-kill culprit and is not
     * reachable via any AOSP API. Callers can pair a true result with the
     * MIUI autostart intent below. Never gates anything — purely advisory.
     */
    fun isLikelyMiui(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        val brand = Build.BRAND?.lowercase().orEmpty()
        return manufacturer == "xiaomi" ||
            brand == "xiaomi" ||
            brand == "redmi" ||
            brand == "poco"
    }

    /**
     * MIUI autostart-management intent. Unresolvable on non-MIUI devices, so
     * callers must guard with [isLikelyMiui] and resolve/try-catch before use.
     */
    fun miuiAutostartIntent(): Intent =
        Intent().apply {
            component =
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
        }
}
