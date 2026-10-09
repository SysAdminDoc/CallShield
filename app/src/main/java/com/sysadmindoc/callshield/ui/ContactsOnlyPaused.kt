package com.sysadmindoc.callshield.ui

import android.Manifest
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.ShapeMd

/**
 * Asks for Contacts access and calls [onResult] once Android answers. Android
 * stops showing its dialog after two refusals, so a refusal that leaves no
 * dialog for next time opens App info instead, where the permission lives.
 */
@Composable
fun rememberAllowContacts(onResult: () -> Unit = {}): () -> Unit {
    val request = rememberPermissionRequest(R.string.contacts_only_allow_in_app_info) { onResult() }
    return { request(listOf(Manifest.permission.READ_CONTACTS)) }
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
