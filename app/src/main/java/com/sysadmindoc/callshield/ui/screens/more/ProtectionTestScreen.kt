package com.sysadmindoc.callshield.ui.screens.more

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.sysadmindoc.callshield.R
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TestPriority { Required, Recommended, Informational }

private data class TestResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
    val priority: TestPriority = TestPriority.Required,
    val recoveryHint: String? = null
)

@Composable
fun ProtectionTestScreen() {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failures = remember(results) { results.filterNot { it.passed } }
    val requiredFailures = remember(failures) { failures.count { it.priority == TestPriority.Required } }
    val summaryColor = when {
        results.isEmpty() -> CatBlue
        requiredFailures > 0 -> CatRed
        failures.isNotEmpty() -> CatYellow
        else -> CatGreen
    }
    val nextSteps = remember(failures) {
        failures.mapNotNull { it.recoveryHint }.distinct().take(3)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                stringResource(R.string.protection_test_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CatGreen,
                letterSpacing = (-0.3).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.protection_test_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
        }

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
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CatGreen.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Black)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.protection_test_testing), color = Black, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = Black)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.protection_test_run_all), color = Black, fontWeight = FontWeight.Bold)
            }
        }

        if (results.isEmpty()) {
            PremiumCard(accentColor = CatBlue) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.protection_test_intro_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CatBlue
                    )
                    Text(
                        stringResource(R.string.protection_test_intro_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext
                    )
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_permissions))
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_engines))
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_integrations))
                }
            }
        } else {
            val passed = results.count { it.passed }
            val total = results.size
            val allPassed = passed == total

            PremiumCard(accentColor = summaryColor) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .accentGlow(summaryColor, 300f, 0.06f)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(summaryColor.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (allPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,
                                tint = summaryColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            val scorePercent = (passed * 100) / total
                            Text(
                                stringResource(R.string.protection_test_summary, passed, total, scorePercent),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (allPassed) {
                                    stringResource(R.string.protection_test_all_ok)
                                } else {
                                    stringResource(R.string.protection_test_issues, total - passed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = summaryColor
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { passed / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                        color = summaryColor,
                        trackColor = CatMuted.copy(alpha = 0.2f)
                    )

                    Column {
                        Text(
                            if (allPassed) {
                                stringResource(R.string.protection_test_summary_body_ok)
                            } else {
                                stringResource(R.string.protection_test_summary_body_attention)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext
                        )
                    }
                }
            }

            if (nextSteps.isNotEmpty()) {
                PremiumCard(accentColor = CatBlue) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.protection_test_next_steps),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CatBlue
                        )
                        nextSteps.forEach { step ->
                            ProtectionIntroRow(step)
                        }

                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.3f))
                        ) {
                            Text(
                                stringResource(R.string.protection_test_open_settings),
                                color = CatBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            val actionNeeded = failures.sortedBy { it.priority.ordinal }
            val passing = results.filter { it.passed }

            if (actionNeeded.isNotEmpty()) {
                Text(
                    stringResource(R.string.protection_test_action_needed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = summaryColor
                )
            }

            actionNeeded.forEachIndexed { index, result ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(index.toLong().coerceAtMost(12) * 50)
                    visible = true
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically { 30 } + fadeIn()
                ) {
                    TestResultCard(result = result)
                }
            }

            if (passing.isNotEmpty()) {
                Text(
                    stringResource(R.string.protection_test_working),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CatGreen
                )
            }

            passing.forEachIndexed { index, result ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(index.toLong().coerceAtMost(12) * 40)
                    visible = true
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically { 20 } + fadeIn()
                ) {
                    TestResultCard(result = result)
                }
            }
        }
    }
}

@Composable
private fun ProtectionIntroRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(CatBlue)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = CatText
        )
    }
}

@Composable
private fun TestResultCard(result: TestResult) {
    val accentColor = when {
        result.passed -> CatGreen.copy(alpha = 0.5f)
        result.priority == TestPriority.Required -> CatRed.copy(alpha = 0.5f)
        else -> CatYellow.copy(alpha = 0.5f)
    }
    val iconTint = when {
        result.passed -> CatGreen
        result.priority == TestPriority.Required -> CatRed
        else -> CatYellow
    }

    PremiumCard(cornerRadius = 14.dp, accentColor = accentColor) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (result.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    result.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                result.recoveryHint?.takeIf { !result.passed }?.let { recovery ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        recovery,
                        style = MaterialTheme.typography.labelSmall,
                        color = iconTint
                    )
                }
            }
        }
    }
}

private suspend fun runTests(context: Context): List<TestResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<TestResult>()
    val repo = SpamRepository.getInstance(context)

    CallShieldPermissions.protectionTestPermissions.forEach { (_, perm) ->
        val granted = CallShieldPermissions.isPermissionGranted(context, perm)
        results.add(
            TestResult(
                name = permissionTestName(context, perm),
                passed = granted,
                detail = if (granted) {
                    context.getString(R.string.protection_test_perm_granted)
                } else {
                    context.getString(R.string.protection_test_perm_not_granted)
                },
                priority = when (perm) {
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS -> TestPriority.Required
                    else -> TestPriority.Recommended
                },
                recoveryHint = if (granted) null else context.getString(R.string.protection_test_fix_permissions)
            )
        )
    }

    val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
    val isScreener = rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ?: false
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_call_screener_role),
            passed = isScreener,
            detail = if (isScreener) {
                context.getString(R.string.protection_test_screener_yes)
            } else {
                context.getString(R.string.protection_test_screener_no)
            },
            priority = TestPriority.Required,
            recoveryHint = if (isScreener) null else context.getString(R.string.protection_test_fix_screener)
        )
    )

    val count = repo.getSpamCount()
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_spam_database),
            passed = count > 0,
            detail = if (count > 0) {
                context.getString(R.string.protection_test_db_count, count)
            } else {
                context.getString(R.string.protection_test_db_empty)
            },
            priority = TestPriority.Required,
            recoveryHint = if (count > 0) null else context.getString(R.string.protection_test_fix_database)
        )
    )

    val testResult = repo.isSpam("+19005551234", realtimeCall = false)
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_prefix_detection),
            passed = testResult.isSpam,
            detail = if (testResult.isSpam) {
                context.getString(R.string.protection_test_prefix_pass, testResult.matchSource)
            } else {
                context.getString(R.string.protection_test_prefix_fail)
            },
            recoveryHint = if (testResult.isSpam) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    val wangiriResult = repo.isSpam("+2321234567", realtimeCall = false)
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_wangiri_detection),
            passed = wangiriResult.isSpam,
            detail = if (wangiriResult.isSpam) {
                context.getString(R.string.protection_test_wangiri_pass)
            } else {
                context.getString(R.string.protection_test_wangiri_fail)
            },
            recoveryHint = if (wangiriResult.isSpam) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    val smsResult = repo.isSpamSms("+15555555555", "You have WON a FREE gift card! Claim now at bit.ly/scam", realtimeCall = false)
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_sms_content_analysis),
            passed = smsResult.isSpam,
            detail = if (smsResult.isSpam) {
                context.getString(R.string.protection_test_sms_pass, smsResult.matchSource)
            } else {
                context.getString(R.string.protection_test_sms_fail)
            },
            recoveryHint = if (smsResult.isSpam) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    val mlResult = com.sysadmindoc.callshield.data.SpamMLScorer.isSpam("+15555550000")
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_ml_spam_scorer),
            passed = mlResult,
            detail = if (mlResult) {
                context.getString(R.string.protection_test_ml_pass)
            } else {
                context.getString(R.string.protection_test_ml_fail)
            },
            recoveryHint = if (mlResult) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    val mlClean = !com.sysadmindoc.callshield.data.SpamMLScorer.isSpam("+12125551234")
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_ml_false_positive),
            passed = mlClean,
            detail = if (mlClean) {
                context.getString(R.string.protection_test_ml_fp_pass)
            } else {
                context.getString(R.string.protection_test_ml_fp_fail)
            },
            recoveryHint = if (mlClean) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    val hotRangesLoaded = com.sysadmindoc.callshield.data.SpamHeuristics.hasHotRanges()
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_hot_list_data),
            passed = hotRangesLoaded,
            detail = if (hotRangesLoaded) {
                context.getString(R.string.protection_test_hot_pass)
            } else {
                context.getString(R.string.protection_test_hot_fail)
            },
            priority = TestPriority.Recommended,
            recoveryHint = if (hotRangesLoaded) null else context.getString(R.string.protection_test_fix_sync)
        )
    )

    val canOverlay = Settings.canDrawOverlays(context)
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_overlay_permission),
            passed = canOverlay,
            detail = if (canOverlay) {
                context.getString(R.string.protection_test_overlay_pass)
            } else {
                context.getString(R.string.protection_test_overlay_fail)
            },
            priority = TestPriority.Recommended,
            recoveryHint = if (canOverlay) null else context.getString(R.string.protection_test_fix_overlay)
        )
    )

    val notifListenerEnabled = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    )?.contains(context.packageName) ?: false
    results.add(
        TestResult(
            name = context.getString(R.string.protection_test_notification_access),
            passed = notifListenerEnabled,
            detail = if (notifListenerEnabled) {
                context.getString(R.string.protection_test_notif_pass)
            } else {
                context.getString(R.string.protection_test_notif_fail)
            },
            priority = TestPriority.Recommended,
            recoveryHint = if (notifListenerEnabled) null else context.getString(R.string.protection_test_fix_notifications)
        )
    )

    val activeCampaigns = com.sysadmindoc.callshield.data.CampaignDetector.getActiveCampaigns()
    results.add(
        TestResult(
            name = context.getString(R.string.test_campaign_detection),
            passed = true,
            detail = context.getString(R.string.test_campaign_monitoring, activeCampaigns.size),
            priority = TestPriority.Informational
        )
    )

    val mlScore = com.sysadmindoc.callshield.data.SpamMLScorer.score("+12025551234")
    val gbtActive = mlScore >= 0.0
    results.add(
        TestResult(
            name = context.getString(R.string.test_ml_model_loaded),
            passed = gbtActive,
            detail = if (gbtActive) {
                context.getString(R.string.test_ml_model_ready)
            } else {
                context.getString(R.string.test_ml_model_fallback)
            },
            priority = TestPriority.Recommended,
            recoveryHint = if (gbtActive) null else context.getString(R.string.protection_test_fix_engine)
        )
    )

    results.add(
        TestResult(
            name = context.getString(R.string.test_after_call_feedback),
            passed = true,
            detail = context.getString(R.string.test_feedback_ready),
            priority = TestPriority.Informational
        )
    )

    results
}

private fun permissionTestName(context: Context, permission: String): String = when (permission) {
    Manifest.permission.READ_CALL_LOG -> context.getString(R.string.protection_test_perm_name_call_log)
    Manifest.permission.READ_CONTACTS -> context.getString(R.string.protection_test_perm_name_contacts)
    Manifest.permission.READ_SMS -> context.getString(R.string.protection_test_perm_name_sms)
    Manifest.permission.RECEIVE_SMS -> context.getString(R.string.protection_test_perm_name_incoming_sms)
    Manifest.permission.READ_PHONE_STATE -> context.getString(R.string.protection_test_perm_name_phone_state)
    Manifest.permission.ANSWER_PHONE_CALLS -> context.getString(R.string.protection_test_perm_name_answer_calls)
    else -> permission
}
