package com.sysadmindoc.callshield.permissions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Who installed this copy, as far as SMS and call log access care.
 *
 * From Android 10, SMS and call log are hard-restricted: an app only gets
 * them when whoever installed it allowlisted them. adb and the phone's own
 * package installer (what a Files app or browser hands an APK to) do. Some
 * third-party stores and installers don't, and then the switch in App info
 * flips straight back to Deny with no message (issue #39). Allow restricted
 * settings doesn't lift that; only installing over the copy with an installer
 * that allowlists them does, and that keeps the app's data.
 */
enum class InstallSourceKind {
    /** adb, or nothing on record. */
    Shell,

    /** The phone's own package installer, or another app that came with the phone. */
    SystemInstaller,

    /** Google Play. */
    PlayStore,

    /** Another app store or installer, which may not allowlist SMS and call log. */
    OtherInstaller,
}

object InstallSource {
    private val packageInstallers =
        setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
        )

    /**
     * Pure classifier, split from the Android reads so it can be unit-tested.
     * An installer that came with the phone counts as the phone's own, since
     * pointing someone at a reinstall that changes nothing is worse than no hint.
     */
    fun classify(
        installingPackageName: String?,
        installerIsSystemApp: Boolean = false,
    ): InstallSourceKind =
        when {
            installingPackageName == null || installingPackageName == "com.android.shell" -> InstallSourceKind.Shell
            installingPackageName in packageInstallers -> InstallSourceKind.SystemInstaller
            installingPackageName == "com.android.vending" -> InstallSourceKind.PlayStore
            installerIsSystemApp -> InstallSourceKind.SystemInstaller
            else -> InstallSourceKind.OtherInstaller
        }

    /** The installer of record for this copy, or null for adb. */
    fun installingPackageName(context: Context): String? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** Live evaluation. The installer of record is always visible to the app it installed. */
    fun evaluate(context: Context): InstallSourceKind {
        val installer = installingPackageName(context)
        return classify(installer, installer != null && installerInfo(context, installer)?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true)
    }

    /** The installer's name as the launcher shows it, or its package name once it's gone. */
    fun installerLabel(context: Context): String? {
        val installer = installingPackageName(context) ?: return null
        return installerInfo(context, installer)?.let { context.packageManager.getApplicationLabel(it).toString() } ?: installer
    }

    private fun installerInfo(
        context: Context,
        installer: String,
    ): ApplicationInfo? =
        try {
            context.packageManager.getApplicationInfo(installer, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
