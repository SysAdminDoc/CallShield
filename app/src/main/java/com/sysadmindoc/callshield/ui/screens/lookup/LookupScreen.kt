package com.sysadmindoc.callshield.ui.screens.lookup

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamCheckResult
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.GradientDivider
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.SectionHeader
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceElevated
import com.sysadmindoc.callshield.ui.theme.accentGlow
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LookupScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var numberInput by remember { mutableStateOf("") }
    val clipboardNumber = remember(context) { clipboardPhoneNumber(context) }
    val normalizedNumber = remember(numberInput) { normalizeLookupNumber(numberInput) }
    val previewLocation = remember(normalizedNumber) { AreaCodeLookup.lookup(normalizedNumber) }
    var result by remember { mutableStateOf<SpamCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val canLookup = normalizedNumber.length >= 5

    fun clearLookup() {
        numberInput = ""
        result = null
        errorMessage = null
    }

    fun runLookup() {
        if (!canLookup || checking) return

        checking = true
        result = null
        errorMessage = null
        scope.launch {
            try {
                val repo = SpamRepository.getInstance(context)
                val lookupResult = withContext(Dispatchers.IO) {
                    repo.isSpam(normalizedNumber, realtimeCall = false)
                }
                result = lookupResult
                haptic(context, lookupResult.isSpam)
            } catch (e: Exception) {
                errorMessage = context.getString(
                    R.string.lookup_failed,
                    e.message ?: context.getString(R.string.lookup_failed_unknown).substringAfter(": ")
                )
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!clipboardNumber.isNullOrBlank() && numberInput.isEmpty()) {
            numberInput = clipboardNumber
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PremiumCard(accentColor = CatGreen, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeader(stringResource(R.string.lookup_title), CatGreen)
                    Text(
                        stringResource(R.string.lookup_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            text = stringResource(R.string.lookup_badge_layers),
                            color = CatGreen
                        )
                        when {
                            previewLocation != null -> StatusPill(
                                text = previewLocation,
                                color = CatYellow
                            )
                            !clipboardNumber.isNullOrBlank() && numberInput.isBlank() -> StatusPill(
                                text = stringResource(R.string.lookup_badge_clipboard),
                                color = CatYellow
                            )
                        }
                    }
                }
            }

            PremiumCard(
                accentColor = when {
                    errorMessage != null -> CatRed
                    canLookup -> CatGreen
                    else -> CatBlue
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = {
                            numberInput = sanitizeLookupInput(it)
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.lookup_phone_number)) },
                        placeholder = { Text(stringResource(R.string.lookup_phone_placeholder)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = stringResource(R.string.cd_phone_input),
                                tint = CatSubtext
                            )
                        },
                        trailingIcon = {
                            if (numberInput.isNotBlank()) {
                                IconButton(onClick = { clearLookup() }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cd_close),
                                        tint = CatOverlay
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { runLookup() }),
                        singleLine = true,
                        supportingText = {
                            if (normalizedNumber.isNotBlank()) {
                                Text(
                                    if (previewLocation != null) {
                                        stringResource(
                                            R.string.lookup_supporting_location,
                                            PhoneFormatter.format(normalizedNumber),
                                            previewLocation
                                        )
                                    } else {
                                        stringResource(
                                            R.string.lookup_supporting_number,
                                            PhoneFormatter.format(normalizedNumber)
                                        )
                                    },
                                    color = CatOverlay
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceElevated,
                            unfocusedContainerColor = SurfaceElevated,
                            focusedTextColor = CatText,
                            unfocusedTextColor = CatText,
                            focusedBorderColor = CatGreen,
                            cursorColor = CatGreen
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!clipboardNumber.isNullOrBlank() && clipboardNumber != numberInput) {
                            OutlinedButton(
                                onClick = {
                                    numberInput = clipboardNumber
                                    errorMessage = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CatYellow.copy(alpha = 0.28f))
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = CatYellow, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.lookup_paste_clipboard), color = CatYellow)
                            }
                        }
                        if (numberInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = { clearLookup() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CatOverlay.copy(alpha = 0.24f))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = CatSubtext, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.lookup_clear), color = CatSubtext)
                            }
                        }
                    }

                    Button(
                        onClick = { runLookup() },
                        enabled = canLookup && !checking,
                        colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f))
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Black
                            )
                        } else {
                            Icon(Icons.Default.Search, null, tint = Black)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lookup_check_number), color = Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            when {
                checking -> LookupProgressCard(normalizedNumber, previewLocation)
                errorMessage != null -> LookupMessageCard(
                    title = stringResource(R.string.lookup_error_title),
                    body = errorMessage!!,
                    accentColor = CatRed,
                    icon = Icons.Default.ErrorOutline
                )
                result != null -> {
                    val lookupResult = result!!
                    val resultAccent = if (lookupResult.isSpam) CatRed else CatGreen
                    val score = if (lookupResult.isSpam) lookupResult.confidence else 0

                    SpamScoreGauge(score = score, isSpam = lookupResult.isSpam)

                    PremiumCard(accentColor = resultAccent, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatusPill(
                                text = if (lookupResult.isSpam) {
                                    stringResource(R.string.lookup_result_high_risk)
                                } else {
                                    stringResource(R.string.lookup_result_clear)
                                },
                                color = resultAccent
                            )
                            Icon(
                                if (lookupResult.isSpam) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = if (lookupResult.isSpam) {
                                    stringResource(R.string.cd_spam_detected)
                                } else {
                                    stringResource(R.string.cd_number_clean)
                                },
                                tint = resultAccent,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                if (lookupResult.isSpam) stringResource(R.string.lookup_spam_detected) else stringResource(R.string.lookup_clean),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = resultAccent
                            )
                            StatusPill(
                                text = PhoneFormatter.format(normalizedNumber),
                                color = if (lookupResult.isSpam) CatPeach else CatBlue
                            )
                            previewLocation?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                            }
                            Text(
                                if (lookupResult.isSpam) {
                                    stringResource(R.string.lookup_result_spam_summary, lookupResult.confidence)
                                } else {
                                    stringResource(R.string.lookup_result_safe_summary)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lookupResult.isSpam) CatSubtext else CatGreen
                            )

                            GradientDivider(color = resultAccent)

                            if (lookupResult.isSpam) {
                                DetailRow(
                                    label = stringResource(R.string.lookup_detection),
                                    value = lookupResult.matchSource.replace("_", " ").replaceFirstChar { it.uppercase() },
                                    icon = detectionIcon(lookupResult.matchSource)
                                )
                                DetailRow(
                                    label = stringResource(R.string.lookup_type),
                                    value = lookupResult.type.replace("_", " ").replaceFirstChar { it.uppercase() }
                                )
                                if (lookupResult.description.isNotEmpty()) {
                                    DetailRow(
                                        label = stringResource(R.string.lookup_details),
                                        value = lookupResult.description
                                    )
                                }
                                DetailRow(
                                    label = stringResource(R.string.lookup_confidence),
                                    value = stringResource(R.string.lookup_confidence_value, lookupResult.confidence)
                                )
                            } else {
                                LookupHintRow(
                                    icon = Icons.Default.VerifiedUser,
                                    title = stringResource(R.string.lookup_mark_trusted),
                                    subtitle = stringResource(R.string.lookup_idle_trusted_body),
                                    accentColor = CatGreen
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lookupResult.isSpam) {
                            Button(
                                onClick = {
                                    val repo = SpamRepository.getInstance(context)
                                    scope.launch {
                                        val message = try {
                                            withContext(Dispatchers.IO) {
                                                repo.blockNumber(normalizedNumber, lookupResult.type, lookupResult.matchSource)
                                            }
                                            hapticConfirm(context)
                                            context.getString(R.string.lookup_number_blocked)
                                        } catch (e: Exception) {
                                            context.getString(R.string.lookup_block_failed, e.message ?: "")
                                        }
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CatRed),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CatRed.copy(alpha = 0.3f))
                            ) {
                                Icon(Icons.Default.Block, null, tint = Black, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.lookup_block), color = Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val message = try {
                                        val repo = SpamRepository.getInstance(context)
                                        withContext(Dispatchers.IO) {
                                            if (lookupResult.isSpam) {
                                                CommunityContributor.contribute(normalizedNumber, lookupResult.type.ifEmpty { "spam" })
                                                context.getString(R.string.lookup_reported)
                                            } else {
                                                repo.addToWhitelist(normalizedNumber, "Marked safe from lookup")
                                                val reportResult = CommunityContributor.reportNotSpam(normalizedNumber)
                                                if (reportResult.success) {
                                                    context.getString(R.string.lookup_marked_safe_reported)
                                                } else {
                                                    context.getString(R.string.lookup_marked_safe_local)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        context.getString(R.string.lookup_report_failed, e.message ?: "")
                                    }
                                    hapticTick(context)
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f))
                        ) {
                            Icon(
                                if (lookupResult.isSpam) Icons.Default.Favorite else Icons.Default.VerifiedUser,
                                null,
                                tint = CatGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (lookupResult.isSpam) {
                                    stringResource(R.string.lookup_report)
                                } else {
                                    stringResource(R.string.lookup_mark_trusted)
                                },
                                color = CatGreen
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.openNumberDetail(normalizedNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CatYellow.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = CatYellow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lookup_open_detail), color = CatYellow)
                    }
                }
                else -> LookupIdleCard()
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun LookupIdleCard() {
    PremiumCard(accentColor = CatBlue, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(stringResource(R.string.lookup_idle_title), CatBlue)
            Text(
                stringResource(R.string.lookup_idle_body),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
            GradientDivider(color = CatBlue)
            LookupHintRow(
                icon = Icons.Default.Psychology,
                title = stringResource(R.string.lookup_idle_signal_title),
                subtitle = stringResource(R.string.lookup_idle_signal_body),
                accentColor = CatPeach
            )
            LookupHintRow(
                icon = Icons.Default.VerifiedUser,
                title = stringResource(R.string.lookup_idle_trusted_title),
                subtitle = stringResource(R.string.lookup_idle_trusted_body),
                accentColor = CatGreen
            )
        }
    }
}

@Composable
private fun LookupProgressCard(number: String, location: String?) {
    PremiumCard(accentColor = CatYellow, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = CatYellow
                )
                Column {
                    Text(
                        stringResource(R.string.lookup_progress_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = CatText
                    )
                    Text(
                        stringResource(R.string.lookup_progress_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (number.isNotBlank()) {
                    StatusPill(text = PhoneFormatter.format(number), color = CatYellow)
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
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(22.dp))
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
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = CatText, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
    }
}

@Composable
fun SpamScoreGauge(score: Int, isSpam: Boolean) {
    val animatedScore by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "gauge"
    )
    val color = when {
        score >= 70 -> CatRed
        score >= 40 -> CatPeach
        score > 0 -> CatYellow
        else -> CatGreen
    }
    val glowColor = if (isSpam) CatRed else CatGreen

    Box(
        modifier = Modifier
            .size(120.dp)
            .accentGlow(glowColor, 200f, 0.07f),
        contentAlignment = Alignment.Center
    ) {
        val bgColor = CatOverlay.copy(alpha = 0.2f)
        Box(
            modifier = Modifier
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
                        size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx())
                    )
                    drawArc(
                        color = color,
                        startAngle = 135f,
                        sweepAngle = 270f * animatedScore,
                        useCenter = false,
                        style = stroke,
                        topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                        size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx())
                    )
                }
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                if (isSpam) stringResource(R.string.lookup_spam) else stringResource(R.string.lookup_safe),
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = CatSubtext, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = CatOverlay,
            modifier = Modifier.width(90.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

fun detectionIcon(source: String): ImageVector = when {
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
private fun haptic(context: android.content.Context, isSpam: Boolean) {
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

private fun sanitizeLookupInput(input: String): String {
    val builder = StringBuilder()
    input.forEach { char ->
        when {
            char.isDigit() -> builder.append(char)
            char == '+' && builder.isEmpty() -> builder.append(char)
            char == ' ' || char == '-' || char == '(' || char == ')' -> builder.append(char)
        }
    }
    return builder.toString().take(24)
}

private fun normalizeLookupNumber(input: String): String {
    val digitsOnly = input.filter { it.isDigit() }
    return if (input.trim().startsWith("+")) {
        "+$digitsOnly"
    } else {
        digitsOnly
    }
}

private fun clipboardPhoneNumber(context: android.content.Context): String? {
    return try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
        val normalized = normalizeLookupNumber(clip)
        normalized.takeIf { it.filter(Char::isDigit).length in 7..15 }
    } catch (_: Exception) {
        null
    }
}
