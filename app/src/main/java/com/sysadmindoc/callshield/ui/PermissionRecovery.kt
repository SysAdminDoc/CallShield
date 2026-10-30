package com.sysadmindoc.callshield.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.util.startActivitySafely

/**
 * Where [rememberPermissionRequest] and [rememberRoleRequest] show their App
 * info and Default apps hints. A snackbar holds the whole hint; the toast they
 * used to show stops at two lines on Android 12 and later, and the settings
 * page it opened covered the rest. With no host the page opens straight away.
 */
val LocalPermissionHints = staticCompositionLocalOf<PermissionHints?> { null }

/** The settings page a hint's action opens. */
internal enum class SettingsPage(
    @param:StringRes val actionLabel: Int,
) {
    AppInfo(R.string.permission_hint_app_info),
    DefaultApps(R.string.permission_hint_default_apps),
    ;

    fun intent(context: Context): Intent =
        when (this) {
            AppInfo -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            DefaultApps -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
}

/** A hint showing or waiting to show. Not a data class, so asking again shows it again. */
internal class SettingsHint(
    @param:StringRes val message: Int,
    val page: SettingsPage,
)

/**
 * The hint [PermissionHintsHost] shows, and the [snackbars] it shows it in.
 * The host holds it, not the screen that asked, so changing tabs doesn't
 * cancel the hint, and it's saved, so a rotation brings it back.
 */
@Stable
class PermissionHints internal constructor(
    val snackbars: SnackbarHostState,
) {
    internal var pending by mutableStateOf<SettingsHint?>(null)
}

/** Hints for a [PermissionHintsHost], shown in [snackbars] alongside the screen's own messages. */
@Composable
fun rememberPermissionHints(snackbars: SnackbarHostState = remember { SnackbarHostState() }): PermissionHints =
    rememberSaveable(
        snackbars,
        saver =
            Saver(
                save = { hints -> hints.pending?.let { intArrayOf(it.message, it.page.ordinal) } },
                restore = { saved ->
                    PermissionHints(snackbars).apply {
                        pending = SettingsPage.entries.getOrNull(saved[1])?.let { SettingsHint(saved[0], it) }
                    }
                },
            ),
    ) { PermissionHints(snackbars) }

private class SettingsHintVisuals(
    override val message: String,
    override val actionLabel: String,
) : SnackbarVisuals {
    override val withDismissAction get() = false
    override val duration get() = SnackbarDuration.Long
}

/**
 * Shows [hints], replacing any message already there with a new settings
 * hint and opening its page from the action. The hint's action sits on its
 * own line so a large font can't squeeze the hint into a narrow column.
 */
@Composable
fun PermissionHintsHost(
    hints: PermissionHints,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    hints.pending?.let { hint ->
        val message = stringResource(hint.message)
        val actionLabel = stringResource(hint.page.actionLabel)
        LaunchedEffect(hint) {
            hints.snackbars.currentSnackbarData?.dismiss()
            val result = hints.snackbars.showSnackbar(SettingsHintVisuals(message, actionLabel))
            if (result == SnackbarResult.ActionPerformed) context.startActivitySafely(hint.page.intent(context))
            hints.pending = null
        }
    }
    SnackbarHost(hints.snackbars, modifier) { Snackbar(it, actionOnNewLine = it.visuals is SettingsHintVisuals) }
}

/** Shows [message] in [hints] with an action that opens [page]. Without [hints] it opens [page] now. */
private fun showSettingsHint(
    hints: PermissionHints?,
    @StringRes message: Int,
    page: SettingsPage,
    context: Context,
) {
    if (hints == null) context.startActivitySafely(page.intent(context)) else hints.pending = SettingsHint(message, page)
}

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
 * time, it calls [onAppInfoHint] and shows [appInfoHint] in [hints] with an
 * App info action, since App info is where the permission lives now. Only
 * refusals of [appInfoFor] count when it's given.
 */
@Composable
fun rememberPermissionRequest(
    @StringRes appInfoHint: Int,
    appInfoFor: Collection<String>? = null,
    hints: PermissionHints? = LocalPermissionHints.current,
    onAppInfoHint: () -> Unit = {},
    onResult: (Map<String, Boolean>) -> Unit = {},
): (List<String>) -> Unit {
    val context = LocalContext.current
    // Saved, since the prompt can outlive the activity (a rotation or a
    // reclaimed process), and the answer is read against them.
    var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
    var refusedBefore by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            onResult(grants)
            val activity = context.findActivity() ?: return@rememberLauncherForActivityResult
            val stillDenied = refusalsThatCount(grants, appInfoFor)
            val promptShown = SystemClock.elapsedRealtime() - launchedAt >= PROMPT_MIN_MILLIS
            if (opensAppInfoAfterRequest(stillDenied, promptShown, refusedBefore, activity::shouldShowRequestPermissionRationale)) {
                onAppInfoHint()
                showSettingsHint(hints, appInfoHint, SettingsPage.AppInfo, context)
            }
        }
    return { permissions ->
        launchedAt = SystemClock.elapsedRealtime()
        refusedBefore = context.findActivity()?.let { activity -> permissions.filter(activity::shouldShowRequestPermissionRationale) }.orEmpty()
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
    /** Android showed no prompt, so the Default apps hint was shown. */
    val defaultAppsHintShown: Boolean,
)

/**
 * Returns a function that starts the role request intent it's given and calls
 * [onResult] with the answer. When the request comes straight back refused,
 * it shows [defaultAppsHint] in [hints] with a Default apps action, since
 * Default apps is where the role lives now.
 */
@Composable
fun rememberRoleRequest(
    @StringRes defaultAppsHint: Int,
    roleHeld: () -> Boolean,
    hints: PermissionHints? = LocalPermissionHints.current,
    onResult: (RoleRequestResult) -> Unit = {},
): (Intent) -> Unit {
    val context = LocalContext.current
    var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val held = roleHeld()
            val hintShown = opensDefaultAppsAfterRoleRequest(held, SystemClock.elapsedRealtime() - launchedAt)
            onResult(RoleRequestResult(held, hintShown))
            if (hintShown) showSettingsHint(hints, defaultAppsHint, SettingsPage.DefaultApps, context)
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
