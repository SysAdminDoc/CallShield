@file:Suppress("FunctionNaming")

package com.sysadmindoc.callshield.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.service.SpamActionReceiver
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.GradientDivider
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.util.normalizePhoneNumberInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Optional Android 11+ review surface launched by Telecom after an eligible completed call. */
class PostCallActivity : AppCompatActivity() {
    private lateinit var details: PostCallDetails

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishAndRemoveTask()
            return
        }

        details = PostCallIntentParser.parse(intent) ?: run {
            finishAndRemoveTask()
            return
        }

        lifecycleScope.launch {
            val repository = SpamRepository.getInstance(applicationContext)
            val enabled =
                runCatching {
                    repository.postCallScreenEnabled.first()
                }.onFailure { Log.w(TAG, "Unable to read post-call preference", it) }
                    .getOrDefault(false)
            if (!enabled) {
                finishAndRemoveTask()
                return@launch
            }

            val appTheme =
                runCatching { AppThemeMode.fromStorage(repository.appTheme.first()) }
                    .onFailure { Log.w(TAG, "Unable to read app theme", it) }
                    .getOrDefault(AppThemeMode.Amoled)

            setContent {
                CallShieldTheme(themeMode = appTheme) {
                    PostCallScreen(
                        details = details,
                        onMarkSpam = ::markSpam,
                        onAddContact = ::addContact,
                        onDismiss = ::finishAndRemoveTask,
                    )
                }
            }
        }
    }

    private fun markSpam() {
        sendBroadcast(
            Intent(this, SpamActionReceiver::class.java)
                .setAction(SpamActionReceiver.ACTION_FEEDBACK_SPAM)
                .putExtra(SpamActionReceiver.EXTRA_FEEDBACK_NUMBER, details.number),
        )
        finishAndRemoveTask()
    }

    private fun addContact() {
        val contactIntent =
            Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, details.number)
                .putExtra(
                    ContactsContract.Intents.Insert.PHONE_TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                )
        try {
            startActivity(contactIntent)
            finishAndRemoveTask()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.post_call_contact_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "PostCallActivity"
    }
}

internal data class PostCallDetails(
    val number: String,
    val durationBucket: Int,
    val disconnectCause: Int,
)

internal object PostCallIntentParser {
    @Suppress("DEPRECATION")
    fun parse(intent: Intent?): PostCallDetails? {
        if (intent?.action != TelecomManager.ACTION_POST_CALL) return null
        val handle = intent.getParcelableExtra<Uri>(TelecomManager.EXTRA_HANDLE)
        val number =
            if (handle?.scheme == "tel") {
                normalizePhoneNumberInput(handle.schemeSpecificPart.orEmpty())
            } else {
                ""
            }
        return if (number.isEmpty()) {
            null
        } else {
            PostCallDetails(
                number = number,
                durationBucket = intent.getIntExtra(TelecomManager.EXTRA_CALL_DURATION, DURATION_UNKNOWN),
                disconnectCause = intent.getIntExtra(TelecomManager.EXTRA_DISCONNECT_CAUSE, DISCONNECT_UNKNOWN),
            )
        }
    }

    private const val DURATION_UNKNOWN = -1
    private const val DISCONNECT_UNKNOWN = -1
}

@Composable
private fun PostCallScreen(
    details: PostCallDetails,
    onMarkSpam: () -> Unit,
    onAddContact: () -> Unit,
    onDismiss: () -> Unit,
) {
    Scaffold(containerColor = Black) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PostCallHeader()
            PremiumCard(accentColor = CatBlue, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.post_call_number_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = CatSubtext,
                    )
                    Text(
                        PhoneFormatter.formatIsolated(details.number),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CatText,
                    )
                    GradientDivider(color = CatBlue)
                    Text(
                        stringResource(durationLabel(details.durationBucket)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CatSubtext,
                    )
                }
            }
            PostCallActions(onMarkSpam, onAddContact)
            Spacer(Modifier.weight(1f))
            PremiumActionButton(
                label = stringResource(R.string.post_call_done),
                icon = Icons.Default.Close,
                color = CatSubtext,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            )
        }
    }
}

@Composable
private fun PostCallHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PremiumIconTile(
            icon = Icons.Default.PhoneInTalk,
            color = CatGreen,
            size = 48.dp,
            iconSize = 24.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.post_call_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CatText,
            )
            Text(
                stringResource(R.string.post_call_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = CatSubtext,
            )
        }
    }
}

@Composable
private fun PostCallActions(
    onMarkSpam: () -> Unit,
    onAddContact: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PremiumActionButton(
            label = stringResource(R.string.post_call_mark_spam),
            icon = Icons.Default.Block,
            color = CatRed,
            onClick = onMarkSpam,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        )
        PremiumActionButton(
            label = stringResource(R.string.post_call_add_contact),
            icon = Icons.Default.PersonAdd,
            color = CatGreen,
            onClick = onAddContact,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            outlined = true,
        )
    }
}

private fun durationLabel(durationBucket: Int): Int =
    when (durationBucket) {
        TelecomManager.DURATION_VERY_SHORT -> R.string.post_call_duration_very_short
        TelecomManager.DURATION_SHORT -> R.string.post_call_duration_short
        TelecomManager.DURATION_MEDIUM -> R.string.post_call_duration_medium
        TelecomManager.DURATION_LONG -> R.string.post_call_duration_long
        else -> R.string.post_call_duration_unknown
    }
