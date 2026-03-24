package com.sysadmindoc.callshield.ui.screens.more

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TestResult(val name: String, val passed: Boolean, val detail: String)

@Composable
fun ProtectionTestScreen() {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Protection Test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = CatGreen)
        Text("Validates all detection layers and permissions are working correctly.", style = MaterialTheme.typography.bodySmall, color = CatSubtext)

        Button(
            onClick = {
                testing = true
                results = emptyList()
                scope.launch {
                    results = runTests(context)
                    testing = false
                }
            },
            enabled = !testing,
            colors = ButtonDefaults.buttonColors(containerColor = CatGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Black)
                Spacer(Modifier.width(8.dp))
                Text("Testing...", color = Black)
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = Black)
                Spacer(Modifier.width(8.dp))
                Text("Run Tests", color = Black, fontWeight = FontWeight.Bold)
            }
        }

        if (results.isNotEmpty()) {
            val passed = results.count { it.passed }
            val total = results.size
            Card(colors = CardDefaults.cardColors(
                containerColor = if (passed == total) CatGreen.copy(alpha = 0.1f) else CatYellow.copy(alpha = 0.1f)
            ), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (passed == total) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null, tint = if (passed == total) CatGreen else CatYellow, modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$passed / $total tests passed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            results.forEach { result ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (result.passed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null, tint = if (result.passed) CatGreen else CatRed, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(result.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(result.detail, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun runTests(context: Context): List<TestResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<TestResult>()
    val repo = SpamRepository.getInstance(context)

    // Permission checks
    val perms = mapOf(
        "Call Log" to Manifest.permission.READ_CALL_LOG,
        "Contacts" to Manifest.permission.READ_CONTACTS,
        "SMS" to Manifest.permission.RECEIVE_SMS,
        "Phone State" to Manifest.permission.READ_PHONE_STATE
    )
    perms.forEach { (name, perm) ->
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        results.add(TestResult("$name Permission", granted, if (granted) "Granted" else "Not granted — feature may not work"))
    }

    // Call screening role
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val isScreener = rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ?: false
        results.add(TestResult("Call Screener Role", isScreener, if (isScreener) "CallShield is the default call screener" else "Not set — go to Settings to enable"))
    }

    // Database check
    val count = repo.getSpamCount()
    results.add(TestResult("Spam Database", count > 0, "$count numbers loaded"))

    // Detection layer test — check a known spam number format
    val testResult = repo.isSpam("+19005551234") // 900 prefix = premium rate
    results.add(TestResult("Prefix Detection", testResult.isSpam, if (testResult.isSpam) "Correctly blocked +1900 prefix (${testResult.matchSource})" else "Failed to detect premium rate number"))

    // Heuristic test — check international premium
    val wangiriResult = repo.isSpam("+2321234567") // Sierra Leone
    results.add(TestResult("Wangiri Detection", wangiriResult.isSpam, if (wangiriResult.isSpam) "Correctly flagged Sierra Leone number" else "Wangiri detection may be disabled"))

    // SMS content test
    val smsResult = repo.isSpamSms("+15555555555", "You have WON a FREE gift card! Claim now at bit.ly/scam")
    results.add(TestResult("SMS Content Analysis", smsResult.isSpam, if (smsResult.isSpam) "Correctly detected spam SMS (${smsResult.matchSource})" else "SMS analysis may be disabled"))

    // ML scorer test
    val mlResult = com.sysadmindoc.callshield.data.SpamMLScorer.isSpam("+15555550000")
    results.add(TestResult("ML Spam Scorer", mlResult, if (mlResult) "Correctly flagged 555-0000 pattern" else "ML scorer may be disabled or weights not loaded"))

    // ML scorer — should NOT flag a normal number
    val mlClean = !com.sysadmindoc.callshield.data.SpamMLScorer.isSpam("+12125551234")
    results.add(TestResult("ML False Positive", mlClean, if (mlClean) "Normal number correctly passed" else "False positive — threshold may be too low"))

    // Hot list data check
    val hotRangesLoaded = com.sysadmindoc.callshield.data.SpamHeuristics.hasHotRanges()
    results.add(TestResult("Hot List Data", hotRangesLoaded, if (hotRangesLoaded) "Hot campaign ranges loaded" else "Empty — will populate on next 30-min sync"))

    // Overlay permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val canOverlay = android.provider.Settings.canDrawOverlays(context)
        results.add(TestResult("Overlay Permission", canOverlay, if (canOverlay) "Can display caller ID overlay" else "Not granted — overlay won't show"))
    }

    // Notification listener (for RCS filter)
    val notifListenerEnabled = android.provider.Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    )?.contains(context.packageName) ?: false
    results.add(TestResult("Notification Access (RCS)", notifListenerEnabled, if (notifListenerEnabled) "RCS filter can monitor messages" else "Not granted — RCS filter won't work"))

    results
}
