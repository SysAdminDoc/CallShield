package com.sysadmindoc.callshield.ui.screens.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.permissions.PermissionCapabilityPriority
import com.sysadmindoc.callshield.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private enum class TestPriority { Required, Recommended, Informational }

private const val SECURITY_PATCH_PART_COUNT = 3
private const val SECURITY_PATCH_MONTH_OFFSET = 1
private const val SECURITY_PATCH_RECENT_DAYS = 90L

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
                letterSpacing = 0.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.protection_test_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
        }

        PremiumActionButton(
            label = if (testing) {
                stringResource(R.string.protection_test_testing)
            } else {
                stringResource(R.string.protection_test_run_all)
            },
            icon = Icons.Default.PlayArrow,
            color = CatGreen,
            onClick = {
                testing = true
                results = emptyList()
                scope.launch {
                    results = runTests(context)
                    testing = false
                }
            },
            enabled = !testing,
            loading = testing,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )

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
                        PremiumIconTile(
                            icon = if (allPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                            color = summaryColor,
                            size = 52.dp,
                            iconSize = 28.dp
                        )
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
                                    val issueCount = total - passed
                                    pluralStringResource(
                                        R.plurals.protection_test_issues,
                                        issueCount,
                                        issueCount
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = summaryColor
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { passed / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
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

                        PremiumActionButton(
                            label = stringResource(R.string.protection_test_open_settings),
                            icon = Icons.Default.Settings,
                            color = CatBlue,
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            outlined = true
                        )
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

    PremiumCard(cornerRadius = 12.dp, accentColor = accentColor) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PremiumIconTile(
                icon = if (result.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                color = iconTint,
                size = 36.dp,
                iconSize = 19.dp
            )
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

    CallShieldPermissions.permissionContractStates(context).forEach { state ->
        val priority = when (state.contract.priority) {
            PermissionCapabilityPriority.Required -> TestPriority.Required
            PermissionCapabilityPriority.Recommended -> TestPriority.Recommended
        }
        results.add(
            TestResult(
                name = context.getString(state.contract.nameRes),
                passed = state.passed,
                detail = if (state.passed) {
                    context.getString(state.detailRes)
                } else {
                    context.getString(state.contract.degradedModeRes)
                },
                priority = priority,
                recoveryHint = state.recoveryHintRes?.takeUnless { state.passed }?.let(context::getString),
            )
        )
    }

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

    val securityPatch = Build.VERSION.SECURITY_PATCH
    val patchRecent = try {
        val parts = securityPatch.split("-").map { it.toInt() }
        if (parts.size == SECURITY_PATCH_PART_COUNT) {
            val patchMillis =
                java.util.GregorianCalendar(
                    parts[0],
                    parts[1] - SECURITY_PATCH_MONTH_OFFSET,
                    parts[2],
                ).timeInMillis
            System.currentTimeMillis() - patchMillis < TimeUnit.DAYS.toMillis(SECURITY_PATCH_RECENT_DAYS)
        } else false
    } catch (_: Exception) { false }
    results.add(
        TestResult(
            name = context.getString(R.string.test_security_patch),
            passed = patchRecent,
            detail = if (patchRecent) {
                context.getString(R.string.test_security_patch_current, securityPatch)
            } else {
                context.getString(R.string.test_security_patch_stale, securityPatch)
            },
            priority = TestPriority.Informational,
            recoveryHint = if (patchRecent) null else context.getString(R.string.test_security_patch_hint),
        )
    )

    results
}
