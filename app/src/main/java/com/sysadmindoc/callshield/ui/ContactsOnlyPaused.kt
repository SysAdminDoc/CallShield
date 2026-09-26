package com.sysadmindoc.callshield.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.ShapeMd
import com.sysadmindoc.callshield.util.startActivitySafely

/**
 * Asks for Contacts access and calls [onResult] once Android answers. Android
 * stops showing its dialog after two refusals, so a refusal that leaves no
 * dialog for next time opens App info instead, where the permission lives.
 */
@Composable
fun rememberAllowContacts(onResult: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onResult()
            val activity = context.findActivity()
            if (!granted && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                Toast.makeText(context, R.string.contacts_only_allow_in_app_info, Toast.LENGTH_LONG).show()
                context.startActivitySafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    return { launcher.launch(Manifest.permission.READ_CONTACTS) }
}

/**
 * Contacts only can't tell a contact from a stranger without Contacts access,
 * so it stays out of the decision and anyone can ring. Shown wherever the mode
 * is on, so a phone that looks locked down says it isn't.
 */
@Composable
fun ContactsOnlyPausedNote(
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CatPeach.copy(alpha = 0.10f), RoundedCornerShape(ShapeMd))
                .padding(start = 12.dp, top = 10.dp, end = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Contacts, contentDescription = null, tint = CatPeach, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Text(
                stringResource(R.string.contacts_only_paused),
                style = MaterialTheme.typography.bodySmall,
                color = CatText,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        TextButton(onClick = onAllow, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.contacts_only_allow), color = CatPeach)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
