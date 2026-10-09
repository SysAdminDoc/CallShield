package com.sysadmindoc.callshield.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sysadmindoc.callshield.util.startActivitySafely

/**
 * True when a permission request came back with something still refused and
 * Android won't show its dialog for any of it again. After two refusals the
 * request returns at once with nothing on screen, so only App info can turn
 * the permission on. A refusal that can still be asked again is left alone.
 * So is a prompt that was shown ([promptShown]) and closed without an answer
 * the first time: Android reads that like a second refusal, with no rationale
 * left, but it isn't one unless the permission was refused before
 * ([refusedBefore], the ones Android still had a rationale for when asked).
 */
internal fun opensAppInfoAfterRequest(
    stillDenied: Collection<String>,
    promptShown: Boolean = false,
    refusedBefore: Collection<String> = emptyList(),
    showsRationale: (String) -> Boolean,
): Boolean {
    if (stillDenied.isEmpty() || stillDenied.any(showsRationale)) return false
    return !promptShown || stillDenied.any { it in refusedBefore }
}

/** The least time a shown prompt takes to come back; under this, none was shown. */
internal const val PROMPT_MIN_MILLIS = 1_000L

/**
 * The refusals in [grants] that decide whether App info opens: every one, or
 * only those in [appInfoFor] when a request also asks for an extra the
 * feature works without.
 */
internal fun refusalsThatCount(
    grants: Map<String, Boolean>,
    appInfoFor: Collection<String>?,
): Set<String> = grants.filter { (permission, granted) -> !granted && (appInfoFor == null || permission in appInfoFor) }.keys

/**
 * Returns a function that asks for the permissions it's given and calls
 * [onResult] with Android's answer. When the answer leaves no dialog for next
 * time, it calls [onOpensAppInfo], shows [appInfoHint] and opens App info,
 * where the permission lives. Only refusals of [appInfoFor] count when it's
 * given.
 */
@Composable
fun rememberPermissionRequest(
    @StringRes appInfoHint: Int,
    appInfoFor: Collection<String>? = null,
    onOpensAppInfo: () -> Unit = {},
    onResult: (Map<String, Boolean>) -> Unit = {},
): (List<String>) -> Unit {
    val context = LocalContext.current
    var launchedAt by remember { mutableLongStateOf(0L) }
    var refusedBefore by remember { mutableStateOf<Set<String>>(emptySet()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            onResult(grants)
            val activity = context.findActivity() ?: return@rememberLauncherForActivityResult
            val stillDenied = refusalsThatCount(grants, appInfoFor)
            val promptShown = SystemClock.elapsedRealtime() - launchedAt >= PROMPT_MIN_MILLIS
            if (opensAppInfoAfterRequest(stillDenied, promptShown, refusedBefore, activity::shouldShowRequestPermissionRationale)) {
                onOpensAppInfo()
                Toast.makeText(context, appInfoHint, Toast.LENGTH_LONG).show()
                context.startActivitySafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    return { permissions ->
        launchedAt = SystemClock.elapsedRealtime()
        refusedBefore = context.findActivity()?.let { activity -> permissions.filter(activity::shouldShowRequestPermissionRationale).toSet() }.orEmpty()
        launcher.launch(permissions.toTypedArray())
    }
}

/**
 * True when a role request came straight back with the role still refused.
 * Once someone picks "Don't ask again" on the role prompt, Android finishes
 * the request at once with nothing on screen, so only Default apps can hand
 * the role over. A refusal that took long enough to show the prompt was a
 * choice, and is left alone.
 */
internal fun opensDefaultAppsAfterRoleRequest(
    roleHeld: Boolean,
    elapsedMillis: Long,
): Boolean = !roleHeld && elapsedMillis in 0 until PROMPT_MIN_MILLIS

/** What a role request came back with. */
data class RoleRequestResult(
    val held: Boolean,
    /** Android showed no prompt, so the hint was shown and Default apps opened. */
    val opensDefaultApps: Boolean,
)

/**
 * Returns a function that starts the role request intent it's given and calls
 * [onResult] with the answer. When the request comes straight back refused,
 * it shows [defaultAppsHint] and opens Default apps, where the role lives.
 */
@Composable
fun rememberRoleRequest(
    @StringRes defaultAppsHint: Int,
    roleHeld: () -> Boolean,
    onResult: (RoleRequestResult) -> Unit = {},
): (Intent) -> Unit {
    val context = LocalContext.current
    var launchedAt by remember { mutableLongStateOf(0L) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val held = roleHeld()
            val opensDefaultApps = opensDefaultAppsAfterRoleRequest(held, SystemClock.elapsedRealtime() - launchedAt)
            onResult(RoleRequestResult(held, opensDefaultApps))
            if (opensDefaultApps) {
                Toast.makeText(context, defaultAppsHint, Toast.LENGTH_LONG).show()
                context.startActivitySafely(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
    return { intent ->
        launchedAt = SystemClock.elapsedRealtime()
        launcher.launch(intent)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
