package com.sysadmindoc.callshield.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import com.sysadmindoc.callshield.R

/**
 * Start [intent], swallowing the [ActivityNotFoundException]/[SecurityException]
 * that browserless devices, minimal AOSP builds, and locked-down ROMs throw when
 * no activity can handle it. Commit 3bb393f fixed this for the More-screen Quick
 * Links; this centralizes the same guard so every primary-surface intent shares
 * it instead of crashing inconsistently.
 *
 * @return true if an activity was launched, false if it was swallowed.
 */
fun Context.startActivitySafely(
    intent: Intent,
    @StringRes noHandlerMessage: Int = R.string.link_no_activity,
    onFailure: (() -> Unit)? = null,
): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        onFailure?.invoke() ?: toast(noHandlerMessage)
        false
    } catch (_: SecurityException) {
        onFailure?.invoke() ?: toast(noHandlerMessage)
        false
    }

/**
 * Open [url] in a browser, falling back to a toast on browserless devices.
 */
fun Context.launchViewUrlSafely(url: String) {
    startActivitySafely(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        noHandlerMessage = R.string.link_no_browser,
    )
}

private fun Context.toast(
    @StringRes message: Int,
) = Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
