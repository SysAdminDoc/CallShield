package com.sysadmindoc.callshield.ui.screens.more

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.sysadmindoc.callshield.R

/** One time Android ended a CallShield process: when, and its `ApplicationExitInfo.REASON_*` code. */
internal data class ProcessExit(
    val timestamp: Long,
    val reason: Int,
)

internal const val PROCESS_EXIT_LIMIT = 5

/**
 * The newest [limit] exits, newest first. Android returns them in that order
 * already; sorting again keeps a phone that doesn't from leading with an old one.
 */
internal fun latestProcessExits(
    exits: List<ProcessExit>,
    limit: Int = PROCESS_EXIT_LIMIT,
): List<ProcessExit> = exits.sortedByDescending { it.timestamp }.take(limit)

/**
 * Why Android last stopped CallShield, from the phone's own record. Null before
 * Android 11, which keeps none. A binder call, so never on the main thread, and
 * nothing it reads leaves the phone.
 */
internal fun readProcessExits(context: Context): List<ProcessExit>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readProcessExitsSinceR(context) else null

@RequiresApi(Build.VERSION_CODES.R)
private fun readProcessExitsSinceR(context: Context): List<ProcessExit> {
    val activityManager = ContextCompat.getSystemService(context, ActivityManager::class.java) ?: return emptyList()
    val recorded =
        runCatching { activityManager.getHistoricalProcessExitReasons(context.packageName, 0, PROCESS_EXIT_LIMIT) }
            .getOrDefault(emptyList())
    return latestProcessExits(recorded.map { ProcessExit(it.timestamp, it.reason) })
}

// The REASON_* values are compile-time constants, so this reads fine on Android 10,
// where no exit is ever recorded to map.
@StringRes
internal fun processExitReasonLabelRes(reason: Int): Int =
    when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> R.string.protection_test_exit_unknown
        ApplicationExitInfo.REASON_EXIT_SELF -> R.string.protection_test_exit_self
        ApplicationExitInfo.REASON_SIGNALED -> R.string.protection_test_exit_signaled
        ApplicationExitInfo.REASON_LOW_MEMORY -> R.string.protection_test_exit_low_memory
        ApplicationExitInfo.REASON_CRASH -> R.string.protection_test_exit_crash
        ApplicationExitInfo.REASON_CRASH_NATIVE -> R.string.protection_test_exit_crash_native
        ApplicationExitInfo.REASON_ANR -> R.string.protection_test_exit_anr
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> R.string.protection_test_exit_initialization_failure
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> R.string.protection_test_exit_permission_change
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> R.string.protection_test_exit_excessive_resource_usage
        ApplicationExitInfo.REASON_USER_REQUESTED -> R.string.protection_test_exit_user_requested
        ApplicationExitInfo.REASON_USER_STOPPED -> R.string.protection_test_exit_user_stopped
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> R.string.protection_test_exit_dependency_died
        ApplicationExitInfo.REASON_FREEZER -> R.string.protection_test_exit_freezer
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> R.string.protection_test_exit_package_state_change
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> R.string.protection_test_exit_package_updated
        // REASON_OTHER, and any reason a newer Android adds.
        else -> R.string.protection_test_exit_other
    }
