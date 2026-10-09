package com.sysadmindoc.callshield.ui.screens.lookup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PastedMessageVerdict
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.signalLabel
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.SurfaceBright
import com.sysadmindoc.callshield.ui.theme.SurfaceVariant
import com.sysadmindoc.callshield.ui.urlThreatLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Lookup checks: a phone number, or a text message pasted in. */
internal enum class LookupMode { NUMBER, MESSAGE }

/** The Number / Message switch at the top of Lookup. */
@Composable
internal fun LookupModeRow(
    mode: LookupMode,
    onModeChange: (LookupMode) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LookupMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = {
                    Text(stringResource(if (option == LookupMode.NUMBER) R.string.lookup_mode_number else R.string.lookup_mode_message))
                },
                shape = RoundedCornerShape(8.dp),
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SurfaceBright,
                        selectedLabelColor = CatGreen,
                        containerColor = Color.Transparent,
                        labelColor = CatSubtext,
                    ),
                border = null,
            )
        }
    }
}

/**
 * Lookup's message mode. A pasted text goes through the content rules an
 * incoming text meets and its links through the URL checks, all on the
 * phone; a remote URL feed sees a link's domain only when the user turned
 * remote lookups on, as for incoming texts.
 */
@Composable
internal fun PastedMessagePanel(viewModel: MainViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val aggressive by viewModel.aggressiveModeEnabled.collectAsStateWithLifecycle()
    val remoteLookup by viewModel.remoteUrlLookupEnabled.collectAsStateWithLifecycle()
    val stripQuery by viewModel.urlStripQueryEnabled.collectAsStateWithLifecycle()
    // Like the number field: only check for a clip until Paste is tapped.
    val clipboardHasText = remember(context) { clipboardHasText(context) }
    var message by rememberSaveable { mutableStateOf("") }
    var verdict by remember { mutableStateOf<PastedMessageVerdict?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val checking = checkJob != null

    // An edit drops the verdict and any check still running for the old text.
    fun edit(text: String) {
        checkJob?.cancel()
        checkJob = null
        verdict = null
        message = text.take(MAX_PASTED_MESSAGE_CHARS)
    }

    fun check() {
        val body = message.trim()
        if (body.isEmpty() || checking) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        checkJob =
            scope.launch {
                try {
                    val links =
                        try {
                            withContext(Dispatchers.IO) {
                                UrlSafetyChecker.checkSmsBody(body, stripQuery = stripQuery, allowRemoteLookup = remoteLookup)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A feed that can't be reached leaves the content verdict standing.
                            emptyList()
                        }
                    verdict = withContext(Dispatchers.Default) { PastedMessageVerdict.of(body, aggressive, links) }
                } finally {
                    if (checkJob == coroutineContext[Job]) checkJob = null
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = message,
            onValueChange = ::edit,
            placeholder = { Text(stringResource(R.string.lookup_message_hint)) },
            trailingIcon = {
                if (message.isNotBlank()) {
                    IconButton(onClick = { edit("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = CatOverlay)
                    }
                } else if (clipboardHasText) {
                    TextButton(
                        onClick = { clipboardText(context)?.let(::edit) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.lookup_paste_clipboard))
                    }
                }
            },
            supportingText = {
                Text(
                    stringResource(if (remoteLookup) R.string.lookup_message_privacy_remote else R.string.lookup_message_privacy),
                    color = CatOverlay,
                )
            },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedTextColor = CatText,
                    unfocusedTextColor = CatText,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = CatGreen,
                ),
        )
        PremiumActionButton(
            label = stringResource(R.string.lookup_check_message),
            icon = Icons.Default.Search,
            color = CatGreen,
            onClick = { check() },
            enabled = message.isNotBlank() && !checking,
            loading = checking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    verdict?.let { PastedMessageVerdictCard(it) }
}

@Composable
private fun PastedMessageVerdictCard(verdict: PastedMessageVerdict) {
    val context = LocalContext.current
    val accent = if (verdict.looksLikeSpam) CatRed else CatGreen
    // A link the URL check named already says it's a spam site.
    val signals = verdict.signals.distinct().filterNot { it == SPAM_DOMAIN_SIGNAL && verdict.dangerousLinks.isNotEmpty() }
    PremiumCard(accentColor = accent, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PremiumIconTile(icon = if (verdict.looksLikeSpam) Icons.Default.Warning else Icons.Default.VerifiedUser, color = accent)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (verdict.looksLikeSpam) R.string.lookup_message_spam_title else R.string.lookup_message_clean_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = CatText,
                    )
                    Text(
                        stringResource(R.string.lookup_message_score, verdict.score, verdict.threshold),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
            verdict.dangerousLinks.forEach { link ->
                VerdictReasonRow(
                    icon = Icons.Default.LinkOff,
                    text = stringResource(R.string.lookup_message_dangerous_link, urlThreatLabels(context, listOf(link)), link.url),
                    tint = CatRed,
                )
            }
            signals.forEach { signal ->
                VerdictReasonRow(icon = Icons.Default.Flag, text = signalLabel(context, signal), tint = accent)
            }
            if (!verdict.looksLikeSpam) {
                Text(
                    stringResource(if (signals.isEmpty()) R.string.lookup_message_clean_body else R.string.lookup_message_below_bar),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
            }
        }
    }
}

@Composable
private fun VerdictReasonRow(
    icon: ImageVector,
    text: String,
    tint: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CatText, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** The clipboard's text, read only on an explicit Paste tap. */
private fun clipboardText(context: android.content.Context): String? =
    try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

private const val SPAM_DOMAIN_SIGNAL = "spam_domain"

/** Room for any real text; the analyzer reads the first 16 KB of longer ones anyway. */
private const val MAX_PASTED_MESSAGE_CHARS = 5_000
