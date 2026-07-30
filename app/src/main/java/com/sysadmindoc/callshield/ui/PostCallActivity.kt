@file:Suppress("FunctionNaming")

package com.sysadmindoc.callshield.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
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
import com.sysadmindoc.callshield.CallShieldApp
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
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.normalizePhoneNumberInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional Android 11+ review surface launched by Telecom after an eligible completed call. */
class PostCallActivity : AppCompatActivity() {
    private lateinit var details: PostCallDetails

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCachedWindowTheme(this)
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
        // Guard against spoofed launches: any app can start this activity with a
        // crafted ACTION_POST_CALL + tel: handle for an arbitrary number. Before
        // submitting a community spam report (which is broadcast to all users), we
        // require a matching recent call in the device call log. If the number has
        // no recent call, the launch is unverified — block it locally only and skip
        // the community contribution so the shared DB can't be weaponized. If we
        // can't check (READ_CALL_LOG not granted, query error), fall back to the
        // normal path so the legitimate flow is never suppressed.
        val number = details.number
        val app = applicationContext
        CallShieldApp.appScope.launch {
            if (PostCallCallLogVerifier.hasRecentCall(app, number) == false) {
                runCatching {
                    SpamRepository
                        .getInstance(app)
                        .blockNumber(number, "spam", app.getString(R.string.desc_blocked_post_call_local))
                }.onFailure { Log.w(TAG, "Local post-call block failed", it) }
            } else {
                app.sendBroadcast(
                    Intent(app, SpamActionReceiver::class.java)
                        .setAction(SpamActionReceiver.ACTION_FEEDBACK_SPAM)
                        .putExtra(SpamActionReceiver.EXTRA_FEEDBACK_NUMBER, number),
                )
            }
        }
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

/**
 * Verifies that a post-call review actually corresponds to a real recent call in
 * the device call log, so a spoofed ACTION_POST_CALL launch can't drive community
 * spam reports for arbitrary numbers.
 */
internal object PostCallCallLogVerifier {
    /** Only treat calls within this window as matching a "just completed" call. */
    private const val RECENT_WINDOW_MS = 15L * 60L * 1000L

    /**
     * @return true if a call to/from [number] exists in the log within the recent
     *   window, false if the log was readable but has no such entry, or null if we
     *   couldn't determine it (permission missing, query error) — callers should
     *   treat null as "don't block the legitimate flow".
     */
    suspend fun hasRecentCall(
        context: Context,
        number: String,
    ): Boolean? =
        withContext(Dispatchers.IO) {
            val targetDigits = filterAsciiDigits(number)
            if (targetDigits.isEmpty()) return@withContext null
            try {
                val sinceStamp = System.currentTimeMillis() - RECENT_WINDOW_MS
                val cursor =
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER),
                        "${CallLog.Calls.DATE} >= ?",
                        arrayOf(sinceStamp.toString()),
                        "${CallLog.Calls.DATE} DESC",
                    ) ?: return@withContext null
                cursor.use { c ->
                    val col = c.getColumnIndex(CallLog.Calls.NUMBER)
                    if (col < 0) return@withContext null
                    while (c.moveToNext()) {
                        val logged = filterAsciiDigits(c.getString(col).orEmpty())
                        if (logged.isNotEmpty() && digitsMatch(logged, targetDigits)) {
                            return@withContext true
                        }
                    }
                }
                false
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }

    /** Match on the last-10-digit tail so +1 / country-code variants still line up. */
    private fun digitsMatch(
        a: String,
        b: String,
    ): Boolean {
        val tailA = a.takeLast(10)
        val tailB = b.takeLast(10)
        return tailA == tailB || a == b
    }
}

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
        // Scaffold's content padding already carries the system-bar insets —
        // adding statusBarsPadding/navigationBarsPadding on top doubled them,
        // sinking the content a status-bar height too low and floating the
        // bottom button above the nav bar.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
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
