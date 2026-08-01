package com.sysadmindoc.callshield.ui.screens.lookup

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.friendlyMatchReasonLabel
import com.sysadmindoc.callshield.ui.friendlySpamTypeLabel
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.GradientDivider
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.SectionHeader
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceElevated
import com.sysadmindoc.callshield.ui.theme.SurfaceVariant
import com.sysadmindoc.callshield.ui.theme.accentGlow
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.hasMinAsciiDigits
import com.sysadmindoc.callshield.util.normalizePhoneNumberInput
import com.sysadmindoc.callshield.util.sanitizePhoneNumberInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LookupScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var numberInput by rememberSaveable { mutableStateOf("") }
    // Only *check for* a text clip here — reading clipboard content fires the
    // system "app pasted from clipboard" toast on Android 12+, and doing that
    // as a side effect of merely opening the tab reads as surveillance for a
    // privacy app. The actual read happens on the explicit Paste tap below.
    val clipboardHasText = remember(context) { clipboardHasText(context) }
    val normalizedNumber = remember(numberInput) { normalizeLookupNumber(numberInput) }
    val previewLocation = remember(normalizedNumber) { AreaCodeLookup.lookup(normalizedNumber) }
    // Lookup outcomes live in the ViewModel so they survive tab switches and
    // rotation, and so the verdict stays bound to the number that was actually
    // checked rather than to whatever is currently typed in the field.
    val outcome by viewModel.lookupOutcome.collectAsStateWithLifecycle()
    val result = outcome?.result
    val trace = outcome?.trace
    val checkedNumber = outcome?.number
    var checking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val canLookup = hasMinAsciiDigits(normalizedNumber)
    val numberBlockedMessage = stringResource(R.string.lookup_number_blocked)
    val reportedMessage = stringResource(R.string.lookup_reported)
    val markedSafeReportedMessage = stringResource(R.string.lookup_marked_safe_reported)
    val markedSafeLocalMessage = stringResource(R.string.lookup_marked_safe_local)
    val markedSafeDescription = stringResource(R.string.desc_marked_safe_from_lookup)

    fun clearLookup() {
        numberInput = ""
        viewModel.clearLookupOutcome()
        errorMessage = null
    }

    fun runLookup() {
        if (!canLookup || checking) return

        checking = true
        viewModel.clearLookupOutcome()
        errorMessage = null
        val requestedNumber = normalizedNumber
        scope.launch {
            try {
                val repo = SpamRepository.getInstance(context)
                val lookupResult =
                    withContext(Dispatchers.IO) {
                        repo.isSpam(requestedNumber, realtimeCall = false)
                    }
                val traceResult =
                    withContext(Dispatchers.IO) {
                        repo.traceRules(requestedNumber)
                    }
                viewModel.recordLookupOutcome(requestedNumber, lookupResult, traceResult)
                haptic(context, lookupResult.isSpam)
            } catch (_: Exception) {
                // Show a localized generic message — never the raw exception text
                // (SQLite constraint names, OkHttp internals) which isn't localized.
                errorMessage = resources.getString(R.string.lookup_failed)
            } finally {
                checking = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = numberInput,
                    onValueChange = {
                        numberInput = sanitizeLookupInput(it)
                        errorMessage = null
                    },
                    placeholder = { Text(stringResource(R.string.lookup_phone_number)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = stringResource(R.string.cd_phone_input),
                            tint = CatSubtext,
                        )
                    },
                    trailingIcon = {
                        if (numberInput.isNotBlank()) {
                            IconButton(onClick = { clearLookup() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_close),
                                    tint = CatOverlay,
                                )
                            }
                        } else if (clipboardHasText) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    clipboardPhoneNumber(context)?.let {
                                        numberInput = it
                                        errorMessage = null
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.lookup_paste_clipboard))
                            }
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Search,
                        ),
                    keyboardActions = KeyboardActions(onSearch = { runLookup() }),
                    singleLine = true,
                    supportingText = {
                        if (normalizedNumber.isNotBlank()) {
                            Text(
                                if (previewLocation != null) {
                                    stringResource(
                                        R.string.lookup_supporting_location,
                                        PhoneFormatter.formatIsolated(normalizedNumber),
                                        previewLocation,
                                    )
                                } else {
                                    stringResource(
                                        R.string.lookup_supporting_number,
                                        PhoneFormatter.formatIsolated(normalizedNumber),
                                    )
                                },
                                color = CatOverlay,
                            )
                        }
                    },
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
                    label = stringResource(R.string.lookup_check_number),
                    icon = Icons.Default.Search,
                    color = CatGreen,
                    onClick = { runLookup() },
                    enabled = canLookup && !checking,
                    loading = checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                checking -> {
                    LookupProgressCard(normalizedNumber, previewLocation)
                }

                errorMessage != null -> {
                    LookupMessageCard(
                        title = stringResource(R.string.lookup_error_title),
                        body = errorMessage!!,
                        accentColor = CatRed,
                        icon = Icons.Default.ErrorOutline,
                    )
                }

                result != null -> {
                    val lookupResult = result!!
                    // This branch describes the number that was checked,
                    // which is not necessarily what is typed in the field now.
                    val resultNumber = checkedNumber ?: normalizedNumber
                    val resultAccent = if (lookupResult.isSpam) CatRed else CatGreen
                    val score = if (lookupResult.isSpam) lookupResult.confidence else 0

                    SpamScoreGauge(score = score, isSpam = lookupResult.isSpam)

                    PremiumCard(accentColor = resultAccent, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusPill(
                                text =
                                    if (lookupResult.isSpam) {
                                        stringResource(R.string.lookup_result_high_risk)
                                    } else {
                                        stringResource(R.string.lookup_result_clear)
                                    },
                                color = resultAccent,
                            )
                            Icon(
                                if (lookupResult.isSpam) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription =
                                    if (lookupResult.isSpam) {
                                        stringResource(R.string.cd_spam_detected)
                                    } else {
                                        stringResource(R.string.cd_number_clean)
                                    },
                                tint = resultAccent,
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                if (lookupResult.isSpam) stringResource(R.string.lookup_spam_detected) else stringResource(R.string.lookup_clean),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = resultAccent,
                            )
                            StatusPill(
                                text = PhoneFormatter.formatIsolated(resultNumber),
                                color = if (lookupResult.isSpam) CatPeach else CatBlue,
                            )
                            AreaCodeLookup.lookup(resultNumber)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                            }
                            Text(
                                if (lookupResult.isSpam) {
                                    stringResource(R.string.lookup_result_spam_summary, lookupResult.confidence)
                                } else {
                                    stringResource(R.string.lookup_result_safe_summary)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lookupResult.isSpam) CatSubtext else CatGreen,
                            )

                            GradientDivider(color = resultAccent)

                            if (lookupResult.isSpam) {
                                DetailRow(
                                    label = stringResource(R.string.lookup_detection),
                                    value = friendlyMatchReasonLabel(lookupResult.matchSource),
                                    icon = detectionIcon(lookupResult.matchSource),
                                )
                                DetailRow(
                                    label = stringResource(R.string.lookup_type),
                                    value = friendlySpamTypeLabel(lookupResult.type),
                                )
                                if (lookupResult.description.isNotEmpty()) {
                                    DetailRow(
                                        label = stringResource(R.string.lookup_details),
                                        value = lookupResult.description,
                                    )
                                }
                                DetailRow(
                                    label = stringResource(R.string.lookup_confidence),
                                    value = stringResource(R.string.lookup_confidence_value, lookupResult.confidence),
                                )
                            } else {
                                LookupHintRow(
                                    icon = Icons.Default.VerifiedUser,
                                    title = stringResource(R.string.lookup_mark_trusted),
                                    subtitle = stringResource(R.string.lookup_idle_trusted_body),
                                    accentColor = CatGreen,
                                )
                            }
                        }
                    }

                    trace?.let { pipelineTrace ->
                        PipelineTraceSection(pipelineTrace)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (lookupResult.isSpam) {
                            PremiumActionButton(
                                label = stringResource(R.string.lookup_block),
                                icon = Icons.Default.Block,
                                color = CatRed,
                                onClick = {
                                    val repo = SpamRepository.getInstance(context)
                                    scope.launch {
                                        val message =
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    repo.blockNumber(resultNumber, lookupResult.type, lookupResult.matchSource)
                                                }
                                                hapticConfirm(context)
                                                numberBlockedMessage
                                            } catch (_: Exception) {
                                                resources.getString(R.string.lookup_block_failed)
                                            }
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PremiumActionButton(
                            label =
                                if (lookupResult.isSpam) {
                                    stringResource(R.string.lookup_report)
                                } else {
                                    stringResource(R.string.lookup_mark_trusted)
                                },
                            icon = if (lookupResult.isSpam) Icons.Default.Flag else Icons.Default.VerifiedUser,
                            color = CatGreen,
                            onClick = {
                                scope.launch {
                                    val message =
                                        try {
                                            val repo = SpamRepository.getInstance(context)
                                            withContext(Dispatchers.IO) {
                                                if (lookupResult.isSpam) {
                                                    CommunityContributor.contribute(
                                                        repo.normalizeNumber(resultNumber),
                                                        lookupResult.type.ifEmpty { "spam" },
                                                    )
                                                    reportedMessage
                                                } else {
                                                    repo.addToWhitelist(resultNumber, markedSafeDescription)
                                                    val reportResult =
                                                        CommunityContributor.reportNotSpam(
                                                            repo.normalizeNumber(resultNumber),
                                                        )
                                                    if (reportResult.success) {
                                                        markedSafeReportedMessage
                                                    } else {
                                                        markedSafeLocalMessage
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {
                                            resources.getString(R.string.lookup_report_failed)
                                        }
                                    hapticTick(context)
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            outlined = true,
                        )
                    }

                    PremiumActionButton(
                        label = stringResource(R.string.lookup_open_detail),
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        color = CatYellow,
                        onClick = { viewModel.openNumberDetail(resultNumber) },
                        modifier = Modifier.fillMaxWidth(),
                        outlined = true,
                    )
                }

                else -> {
                    LookupIdleCard()
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        )
    }
}

@Composable
private fun LookupIdleCard() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(stringResource(R.string.lookup_idle_title))
        GradientDivider()
        LookupHintRow(
            icon = Icons.Default.Psychology,
            title = stringResource(R.string.lookup_idle_signal_title),
            subtitle = stringResource(R.string.lookup_idle_signal_body),
            accentColor = CatSubtext,
        )
        LookupHintRow(
            icon = Icons.Default.VerifiedUser,
            title = stringResource(R.string.lookup_idle_trusted_title),
            subtitle = stringResource(R.string.lookup_idle_trusted_body),
            accentColor = CatGreen,
        )
    }
}

@Composable
private fun LookupProgressCard(
    number: String,
    location: String?,
) {
    PremiumCard(accentColor = CatYellow, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = CatYellow,
                )
                Column {
                    Text(
                        stringResource(R.string.lookup_progress_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = CatText,
                    )
                    Text(
                        stringResource(R.string.lookup_progress_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (number.isNotBlank()) {
                    StatusPill(text = PhoneFormatter.formatIsolated(number), color = CatYellow)
                }
                if (location != null) {
                    StatusPill(text = location, color = CatBlue)
                }
            }
        }
    }
}

@Composable
private fun LookupMessageCard(
    title: String,
    body: String,
    accentColor: Color,
    icon: ImageVector,
) {
    PremiumCard(accentColor = accentColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PremiumIconTile(icon = icon, color = accentColor)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = CatText)
                Text(body, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
        }
    }
}

@Composable
private fun LookupHintRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PremiumIconTile(icon = icon, color = accentColor, size = 38.dp, iconSize = 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = CatText, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
    }
}

@Composable
fun SpamScoreGauge(
    score: Int,
    isSpam: Boolean,
) {
    val animatedScore by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "gauge",
    )
    val color =
        when {
            score >= 70 -> CatRed
            score >= 40 -> CatPeach
            score > 0 -> CatYellow
            else -> CatGreen
        }
    val glowColor = if (isSpam) CatRed else CatGreen

    Box(
        modifier =
            Modifier
                .size(120.dp)
                .accentGlow(glowColor, 200f, 0.07f),
        contentAlignment = Alignment.Center,
    ) {
        val bgColor = CatOverlay.copy(alpha = 0.2f)
        Box(
            modifier =
                Modifier
                    .size(120.dp)
                    .drawBehind {
                        val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(
                            color = bgColor,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = stroke,
                            topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                            size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
                        )
                        drawArc(
                            color = color,
                            startAngle = 135f,
                            sweepAngle = 270f * animatedScore,
                            useCenter = false,
                            style = stroke,
                            topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                            size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
                        )
                    },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                if (isSpam) stringResource(R.string.lookup_spam) else stringResource(R.string.lookup_safe),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = CatSubtext, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = CatOverlay,
            modifier = Modifier.width(90.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

fun detectionIcon(source: String): ImageVector =
    when {
        "database" in source || "hot_list" in source -> Icons.Default.Storage
        "heuristic" in source || "hot_campaign" in source -> Icons.Default.Psychology
        "sms_content" in source || "spam_domain" in source -> Icons.Default.Sms
        "ml_scorer" in source -> Icons.Default.SmartToy
        "rcs_" in source -> Icons.Default.MarkChatRead
        "prefix" in source || "wildcard" in source -> Icons.Default.FilterAlt
        "stir" in source || "time" in source -> Icons.Default.VerifiedUser
        "neighbor" in source -> Icons.Default.NearMe
        "frequency" in source -> Icons.Default.Repeat
        "user" in source -> Icons.Default.Person
        "keyword" in source -> Icons.Default.TextFields
        "context_trust" in source -> Icons.Default.Handshake
        else -> Icons.Default.Warning
    }

@Suppress("DEPRECATION")
private fun haptic(
    context: android.content.Context,
    isSpam: Boolean,
) {
    // Honor the system "Touch feedback" setting.
    if (android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) == 0
    ) {
        return
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val v = vm?.defaultVibrator
            v?.vibrate(VibrationEffect.createOneShot(if (isSpam) 100 else 30, if (isSpam) 200 else 50))
        } else {
            val v = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(if (isSpam) 100 else 30, if (isSpam) 200 else 50))
        }
    } catch (_: Exception) {
    }
}

private fun sanitizeLookupInput(input: String): String = sanitizePhoneNumberInput(input)

private fun normalizeLookupNumber(input: String): String = normalizePhoneNumberInput(input)

@Composable
private fun PipelineTraceSection(trace: com.sysadmindoc.callshield.data.checker.PipelineTrace) {
    var expanded by remember { mutableStateOf(false) }
    val activeEntries =
        trace.entries.filter {
            it.verdict != com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.PASS &&
                it.verdict != com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.DISABLED
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FilterAlt,
                        contentDescription = null,
                        tint = if (trace.hasConflict) CatYellow else CatBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.lookup_pipeline_trace),
                        style = MaterialTheme.typography.labelLarge,
                        color = CatText,
                    )
                    if (trace.hasConflict) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(stringResource(R.string.lookup_conflict), CatYellow)
                    }
                }
                androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.lookup_trace_hide)
                        } else {
                            stringResource(R.string.lookup_trace_show, activeEntries.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                trace.entries.forEach { entry ->
                    val (icon, tint, label) =
                        when (entry.verdict) {
                            com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.BLOCK -> {
                                Triple(Icons.Default.Block, CatRed, stringResource(R.string.lookup_trace_block))
                            }

                            com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.ALLOW -> {
                                Triple(Icons.Default.CheckCircle, CatGreen, stringResource(R.string.lookup_trace_allow))
                            }

                            com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.DISABLED -> {
                                Triple(Icons.Default.Close, CatOverlay, stringResource(R.string.lookup_trace_off))
                            }

                            com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.PASS -> {
                                Triple(Icons.Default.Remove, CatSubtext, stringResource(R.string.lookup_trace_pass))
                            }
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            entry.checkerName.replace("_", " ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = tint,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

/** Toast-free presence check: ClipDescription can be inspected without reading clip data. */
private fun clipboardHasText(context: android.content.Context): Boolean =
    try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.hasPrimaryClip() &&
            clipboard.primaryClipDescription?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true
    } catch (_: Exception) {
        false
    }

private fun clipboardPhoneNumber(context: android.content.Context): String? {
    return try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString() ?: return null
        val normalized = normalizeLookupNumber(clip)
        normalized.takeIf { filterAsciiDigits(it).length in 7..15 }
    } catch (_: Exception) {
        null
    }
}
