package com.sysadmindoc.callshield.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.sysadmindoc.callshield.util.startActivitySafely

/**
 * True when a permission request came back with something still refused and
 * Android won't show its dialog for any of it again. After two refusals the
 * request returns at once with nothing on screen, so only App info can turn
 * the permission on. A refusal that can still be asked again is left alone.
 */
internal fun opensAppInfoAfterRequest(
    stillDenied: Collection<String>,
    showsRationale: (String) -> Boolean,
): Boolean = stillDenied.isNotEmpty() && stillDenied.none(showsRationale)

/**
 * Returns a function that asks for the permissions it's given and calls
 * [onResult] with Android's answer. When the answer leaves no dialog for next
 * time, it shows [appInfoHint] and opens App info, where the permission lives.
 */
@Composable
fun rememberPermissionRequest(
    @StringRes appInfoHint: Int,
    onResult: (Map<String, Boolean>) -> Unit = {},
): (List<String>) -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            onResult(grants)
            val activity = context.findActivity() ?: return@rememberLauncherForActivityResult
            val stillDenied = grants.filterValues { granted -> !granted }.keys
            if (opensAppInfoAfterRequest(stillDenied, activity::shouldShowRequestPermissionRationale)) {
                Toast.makeText(context, appInfoHint, Toast.LENGTH_LONG).show()
                context.startActivitySafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    return { permissions -> launcher.launch(permissions.toTypedArray()) }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
