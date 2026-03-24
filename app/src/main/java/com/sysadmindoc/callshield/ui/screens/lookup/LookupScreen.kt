package com.sysadmindoc.callshield.ui.screens.lookup

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamCheckResult
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LookupScreen() {
    val context = LocalContext.current
    var numberInput by remember { mutableStateOf("") }

    // Auto-paste from clipboard if it contains a phone number
    LaunchedEffect(Unit) {
        try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val digits = clip.filter { it.isDigit() }
            if (digits.length in 7..15 && numberInput.isEmpty()) {
                numberInput = clip.trim()
            }
        } catch (_: Exception) {}
    }
    var result by remember { mutableStateOf<SpamCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Number Lookup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = CatGreen)
        Text("Check any phone number against all 15 detection layers + ML", style = MaterialTheme.typography.bodySmall, color = CatSubtext)

        OutlinedTextField(
            value = numberInput, onValueChange = { numberInput = it },
            label = { Text("Phone Number") },
            placeholder = { Text("+12125551234") },
            leadingIcon = { Icon(Icons.Default.Phone, null, tint = CatSubtext) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (numberInput.length >= 5) {
                    checking = true
                    scope.launch {
                        val repo = SpamRepository.getInstance(context)
                        result = withContext(Dispatchers.IO) { repo.isSpam(numberInput) }
                        checking = false
                        // Haptic feedback
                        haptic(context, result?.isSpam == true)
                    }
                }
            }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen)
        )

        Button(
            onClick = {
                if (numberInput.length >= 5) {
                    checking = true
                    scope.launch {
                        val repo = SpamRepository.getInstance(context)
                        result = withContext(Dispatchers.IO) { repo.isSpam(numberInput) }
                        checking = false
                        haptic(context, result?.isSpam == true)
                    }
                }
            },
            enabled = numberInput.length >= 5 && !checking,
            colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Black)
            } else {
                Icon(Icons.Default.Search, null, tint = Black)
            }
            Spacer(Modifier.width(8.dp))
            Text("Check Number", color = Black, fontWeight = FontWeight.Bold)
        }

        result?.let { r ->
            Spacer(Modifier.height(8.dp))

            // Spam score gauge
            val score = if (r.isSpam) r.confidence else 0
            SpamScoreGauge(score = score, isSpam = r.isSpam)

            // Result card
            Card(
                colors = CardDefaults.cardColors(containerColor = if (r.isSpam) CatRed.copy(alpha = 0.1f) else CatGreen.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (r.isSpam) Icons.Default.Warning else Icons.Default.CheckCircle,
                        null, tint = if (r.isSpam) CatRed else CatGreen, modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (r.isSpam) "SPAM DETECTED" else "CLEAN",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = if (r.isSpam) CatRed else CatGreen
                    )

                    Text(PhoneFormatter.format(numberInput), style = MaterialTheme.typography.bodyLarge, color = CatText)
                    AreaCodeLookup.lookup(numberInput)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                    }

                    if (r.isSpam) {
                        Spacer(Modifier.height(12.dp))
                        DetailRow("Detection", r.matchSource.replace("_", " ").replaceFirstChar { it.uppercase() }, detectionIcon(r.matchSource))
                        DetailRow("Type", r.type.replace("_", " ").replaceFirstChar { it.uppercase() })
                        if (r.description.isNotEmpty()) DetailRow("Details", r.description)
                        DetailRow("Confidence", "${r.confidence}%")
                    }
                }
            }
        }
    }
}

@Composable
fun SpamScoreGauge(score: Int, isSpam: Boolean) {
    val animatedScore by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing), label = "gauge"
    )
    val color = when {
        score >= 70 -> CatRed
        score >= 40 -> CatPeach
        score > 0 -> CatYellow
        else -> CatGreen
    }

    Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        // Arc background
        val bgColor = CatOverlay.copy(alpha = 0.2f)
        val arcColor = color
        Box(modifier = Modifier.size(120.dp).drawBehind {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            drawArc(bgColor, 135f, 270f, false, style = stroke, topLeft = Offset(6.dp.toPx(), 6.dp.toPx()), size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()))
            drawArc(arcColor, 135f, 270f * animatedScore, false, style = stroke, topLeft = Offset(6.dp.toPx(), 6.dp.toPx()), size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(if (isSpam) "SPAM" else "SAFE", style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = CatSubtext, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = CatOverlay, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatText)
    }
}

fun detectionIcon(source: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    "database" in source || "hot_list" in source -> Icons.Default.Storage
    "heuristic" in source || "hot_campaign" in source -> Icons.Default.Psychology
    "sms_content" in source || "spam_domain" in source -> Icons.Default.Sms
    "ml_scorer" in source -> Icons.Default.SmartToy
    "rcs_" in source -> Icons.Default.MarkChatRead
    "prefix" in source -> Icons.Default.FilterAlt
    "wildcard" in source -> Icons.Default.Code
    "stir" in source -> Icons.Default.VerifiedUser
    "neighbor" in source -> Icons.Default.NearMe
    "frequency" in source -> Icons.Default.Repeat
    "time" in source -> Icons.Default.Bedtime
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
    } catch (_: Exception) {}
}
