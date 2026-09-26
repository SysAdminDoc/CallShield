package com.sysadmindoc.callshield.ui.screens.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.MessageCapabilitySource
import com.sysadmindoc.callshield.data.MessageCapabilityState
import com.sysadmindoc.callshield.data.MessageCapabilityStatus
import com.sysadmindoc.callshield.data.ModelHealth
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.HotDataHealth
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.permissions.PermissionCapabilityPriority
import com.sysadmindoc.callshield.permissions.PermissionCapabilityStatus
import com.sysadmindoc.callshield.service.HotDataSync
import com.sysadmindoc.callshield.service.WorkerDiagnostic
import com.sysadmindoc.callshield.service.WorkerDiagnostics
import com.sysadmindoc.callshield.ui.reasonCodeLabelRes
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.HotFeedFreshness
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val recoveryHint: String? = null,
)

private fun workerDiagnosticResult(
    context: Context,
    diagnostic: WorkerDiagnostic,
): TestResult {
    val state = context.getString(WorkerDiagnostics.stateLabelRes(diagnostic.state))
    val stopReason = context.getString(WorkerDiagnostics.stopReasonLabelRes(diagnostic.stopReason))
    val status =
        context.getString(
            R.string.protection_test_worker_status,
            state,
            diagnostic.runAttemptCount,
            stopReason,
        )
    return if (diagnostic.hasRepeatedQuotaStops) {
        TestResult(
            name = context.getString(diagnostic.labelRes),
            passed = false,
            detail = context.getString(R.string.protection_test_worker_quota_warning, status),
            priority = TestPriority.Recommended,
            recoveryHint = context.getString(R.string.protection_test_worker_quota_hint),
        )
    } else {
        TestResult(
            name = context.getString(diagnostic.labelRes),
            passed = true,
            detail = status,
            priority = TestPriority.Informational,
        )
    }
}

internal enum class ModelHealthSeverity { Healthy, Info, Warning, Error }

internal data class ModelHealthUiState(
    @param:StringRes val statusRes: Int,
    @param:StringRes val detailRes: Int,
    val severity: ModelHealthSeverity,
)

internal fun modelHealthUiState(health: ModelHealth): ModelHealthUiState =
    when (health) {
        ModelHealth.GBT_ACTIVE -> {
            ModelHealthUiState(
                R.string.model_health_gbt_active,
                R.string.model_health_gbt_active_detail,
                ModelHealthSeverity.Healthy,
            )
        }

        ModelHealth.LR_ACTIVE -> {
            ModelHealthUiState(
                R.string.model_health_lr_active,
                R.string.model_health_lr_active_detail,
                ModelHealthSeverity.Healthy,
            )
        }

        ModelHealth.DEGRADED_TO_LR -> {
            ModelHealthUiState(
                R.string.model_health_degraded,
                R.string.model_health_degraded_detail,
                ModelHealthSeverity.Warning,
            )
        }

        ModelHealth.PARSE_FAILED -> {
            ModelHealthUiState(
                R.string.model_health_parse_failed,
                R.string.model_health_parse_failed_detail,
                ModelHealthSeverity.Error,
            )
        }

        ModelHealth.DEFAULTS -> {
            ModelHealthUiState(
                R.string.model_health_defaults,
                R.string.model_health_defaults_detail,
                ModelHealthSeverity.Warning,
            )
        }

        ModelHealth.UNINITIALIZED -> {
            ModelHealthUiState(
                R.string.model_health_loading,
                R.string.model_health_loading_detail,
                ModelHealthSeverity.Info,
            )
        }
    }

@Composable
fun ProtectionTestScreen() {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var runFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failures = remember(results) { results.filterNot { it.passed } }
    val requiredFailures = remember(failures) { failures.count { it.priority == TestPriority.Required } }
    val summaryColor =
        when {
            results.isEmpty() -> CatBlue
            requiredFailures > 0 -> CatRed
            failures.isNotEmpty() -> CatYellow
            else -> CatGreen
        }
    val nextSteps =
        remember(failures) {
            failures.mapNotNull { it.recoveryHint }.distinct().take(3)
        }

    fun runAllTests() {
        testing = true
        results = emptyList()
        runFailed = false
        scope.launch {
            try {
                results = runTests(context)
            } catch (_: Exception) {
                runFailed = true
            } finally {
                testing = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.protection_test_system_check),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CatText,
            )
            Text(
                stringResource(R.string.protection_test_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = CatSubtext,
            )
        }

        // Poll until the model resolves so entering this screen during model
        // load shows "pending" then updates to the real status without needing
        // an unrelated recomposition.
        val modelHealth by produceState(initialValue = SpamMLScorer.modelHealth()) {
            while (value == ModelHealth.UNINITIALIZED) {
                delay(500)
                value = SpamMLScorer.modelHealth()
            }
        }
        if (results.isEmpty()) {
            ModelHealthCard(modelHealth)
        }

        if (results.isEmpty()) {
            PremiumActionButton(
                label =
                    if (testing) {
                        stringResource(R.string.protection_test_testing)
                    } else {
                        stringResource(R.string.protection_test_run_all)
                    },
                icon = Icons.Default.PlayArrow,
                color = CatGreen,
                onClick = ::runAllTests,
                enabled = !testing,
                loading = testing,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }

        if (runFailed) {
            PremiumCard(accentColor = CatRed) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.protection_test_run_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CatRed,
                    )
                    Text(
                        stringResource(R.string.protection_test_run_failed_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
        }

        if (results.isEmpty()) {
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(stringResource(R.string.protection_test_intro_title), CatGreen)
                    Text(
                        stringResource(R.string.protection_test_intro_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_permissions), CatGreen)
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_engines), CatGreen)
                    ProtectionIntroRow(stringResource(R.string.protection_test_intro_integrations), CatGreen)
                }
            }
        } else {
            val passed = results.count { it.passed }
            val total = results.size
            val allPassed = passed == total
            val (required, optional) = remember(results) { results.partition { it.priority == TestPriority.Required } }

            PremiumCard(accentColor = summaryColor) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PremiumIconTile(
                        icon = if (allPassed) Icons.Default.VerifiedUser else Icons.Default.Warning,
                        color = summaryColor,
                        size = 44.dp,
                        iconSize = 26.dp,
                        showContainer = true,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            if (allPassed) {
                                stringResource(R.string.protection_test_all_passed_title)
                            } else {
                                val scorePercent = (passed * 100) / total
                                stringResource(R.string.protection_test_summary, passed, total, scorePercent)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CatText,
                        )
                        Text(
                            if (allPassed) {
                                stringResource(R.string.protection_test_ready_body)
                            } else {
                                stringResource(R.string.protection_test_summary_body_attention)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext,
                        )
                        Text(
                            stringResource(R.string.protection_test_last_tested_now),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatOverlay,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CheckTally(
                    label = stringResource(R.string.protection_test_required_tally),
                    note = stringResource(R.string.protection_test_required_note),
                    passed = required.count { it.passed },
                    total = required.size,
                    shortfallColor = CatRed,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                CheckTally(
                    label = stringResource(R.string.protection_test_optional_tally),
                    note = stringResource(R.string.protection_test_optional_note),
                    passed = optional.count { it.passed },
                    total = optional.size,
                    shortfallColor = CatYellow,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            PremiumActionButton(
                label = stringResource(R.string.protection_test_run_again),
                icon = Icons.Default.PlayArrow,
                color = CatGreen,
                onClick = ::runAllTests,
                enabled = !testing,
                loading = testing,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )

            if (nextSteps.isNotEmpty()) {
                PremiumCard(accentColor = CatBlue) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.protection_test_next_steps),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CatBlue,
                        )
                        nextSteps.forEach { step ->
                            ProtectionIntroRow(step)
                        }

                        PremiumActionButton(
                            label = stringResource(R.string.protection_test_open_settings),
                            icon = Icons.Default.Settings,
                            color = CatBlue,
                            onClick = {
                                context.startActivitySafely(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            outlined = true,
                        )
                    }
                }
            }

            val actionNeeded = failures.sortedBy { it.priority.ordinal }
            val passing = results.filter { it.passed }

            if (actionNeeded.isNotEmpty()) {
                SectionHeader(stringResource(R.string.protection_test_action_needed), summaryColor)
            }

            actionNeeded.forEachIndexed { index, result ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(index.toLong().coerceAtMost(12) * 50)
                    visible = true
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically { 30 } + fadeIn(),
                ) {
                    TestResultCard(result = result)
                }
            }

            if (passing.isNotEmpty()) {
                SectionHeader(stringResource(R.string.protection_test_working), CatGreen)
            }

            passing.forEachIndexed { index, result ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(index.toLong().coerceAtMost(12) * 40)
                    visible = true
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically { 20 } + fadeIn(),
                ) {
                    TestResultCard(result = result)
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ModelHealthCard(health: ModelHealth) {
    val state = modelHealthUiState(health)
    val accentColor =
        when (state.severity) {
            ModelHealthSeverity.Healthy -> CatGreen
            ModelHealthSeverity.Info -> CatBlue
            ModelHealthSeverity.Warning -> CatYellow
            ModelHealthSeverity.Error -> CatRed
        }
    val icon =
        when (state.severity) {
            ModelHealthSeverity.Healthy -> Icons.Default.CheckCircle
            ModelHealthSeverity.Info -> Icons.Default.Info
            ModelHealthSeverity.Warning -> Icons.Default.Warning
            ModelHealthSeverity.Error -> Icons.Default.Warning
        }

    PremiumCard(
        modifier = Modifier.fillMaxWidth().testTag("model_health_status"),
        accentColor = accentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PremiumIconTile(
                icon = icon,
                color = accentColor,
                size = 40.dp,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.model_health_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = CatSubtext,
                )
                Text(
                    stringResource(state.statusRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
                Text(
                    stringResource(state.detailRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
            }
        }
    }
}

@Composable
private fun CheckTally(
    label: String,
    note: String,
    passed: Int,
    total: Int,
    shortfallColor: Color,
    modifier: Modifier = Modifier,
) {
    val barColor = if (passed == total) CatGreen else shortfallColor
    val tallyDescription = stringResource(R.string.protection_test_tally_description, passed, total)
    LedgerCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = CatSubtext)
            Text(
                stringResource(R.string.protection_test_tally, passed, total),
                modifier = Modifier.semantics { contentDescription = tallyDescription },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CatText,
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 1f else passed / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(ShapeXs)),
                color = barColor,
                trackColor = CatMuted.copy(alpha = 0.2f),
            )
            Text(note, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
    }
}

@Composable
private fun ProtectionIntroRow(
    text: String,
    dotColor: Color = CatBlue,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = CatText,
        )
    }
}

@Composable
private fun TestResultCard(result: TestResult) {
    val requiredFailure = !result.passed && result.priority == TestPriority.Required
    val iconTint =
        when {
            result.passed -> CatGreen
            requiredFailure -> CatRed
            else -> CatYellow
        }
    // A text status, not a pill: the checks carry no per-row action, so nothing
    // here may look like a button (and the product bans pill backdrops).
    val statusLabel =
        stringResource(
            when {
                result.passed -> R.string.protection_test_status_ok
                requiredFailure -> R.string.protection_test_status_fix
                else -> R.string.protection_test_status_review
            },
        )

    LedgerCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    when {
                        result.passed -> Icons.Default.CheckCircle
                        requiredFailure -> Icons.Default.Error
                        else -> Icons.Default.Warning
                    },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CatText,
                )
                Text(
                    result.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
                result.recoveryHint?.takeIf { !result.passed }?.let { recovery ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        recovery,
                        style = MaterialTheme.typography.labelSmall,
                        color = iconTint,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(text = statusLabel, color = iconTint)
        }
    }
}

private suspend fun runTests(context: Context): List<TestResult> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()
        val repo = SpamRepository.getInstance(context)

        val contractStates =
            CallShieldPermissions.permissionContractStates(
                context,
                answerHangUpEnabled = repo.answerHangUpEnabled.first(),
            )
        contractStates.forEach { state ->
            val priority =
                when (state.contract.priority) {
                    PermissionCapabilityPriority.Required -> TestPriority.Required
                    PermissionCapabilityPriority.Recommended -> TestPriority.Recommended
                }
            results.add(
                TestResult(
                    name = context.getString(state.contract.nameRes),
                    passed = state.passed,
                    detail =
                        if (state.status == PermissionCapabilityStatus.Advisory || state.passed) {
                            context.getString(state.detailRes)
                        } else {
                            context.getString(state.contract.degradedModeRes)
                        },
                    priority = priority,
                    recoveryHint = state.recoveryHintRes?.takeUnless { state.passed }?.let(context::getString),
                ),
            )
        }

        results.add(messageCapabilityTestResult(context, repo.smsMessageCapabilityStatus.first()))
        results.add(messageCapabilityTestResult(context, repo.notificationMessageCapabilityStatus.first()))

        val count = repo.getSpamCount()
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_spam_database),
                passed = count > 0,
                detail =
                    if (count > 0) {
                        context.getString(R.string.protection_test_db_count, count)
                    } else {
                        context.getString(R.string.protection_test_db_empty)
                    },
                priority = TestPriority.Required,
                recoveryHint = if (count > 0) null else context.getString(R.string.protection_test_fix_database),
            ),
        )

        val testResult = repo.isSpam("+19005551234", realtimeCall = false)
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_prefix_detection),
                passed = testResult.isSpam,
                detail =
                    if (testResult.isSpam) {
                        context.getString(R.string.protection_test_prefix_pass, context.getString(reasonCodeLabelRes(testResult.reasonCode)))
                    } else {
                        context.getString(R.string.protection_test_prefix_fail)
                    },
                recoveryHint = if (testResult.isSpam) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        val wangiriResult = repo.isSpam("+2321234567", realtimeCall = false)
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_wangiri_detection),
                passed = wangiriResult.isSpam,
                detail =
                    if (wangiriResult.isSpam) {
                        context.getString(R.string.protection_test_wangiri_pass)
                    } else {
                        context.getString(R.string.protection_test_wangiri_fail)
                    },
                recoveryHint = if (wangiriResult.isSpam) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        val smsResult = repo.isSpamSms("+15555555555", "You have WON a FREE gift card! Claim now at bit.ly/scam", realtimeCall = false)
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_sms_content_analysis),
                passed = smsResult.isSpam,
                detail =
                    if (smsResult.isSpam) {
                        context.getString(R.string.protection_test_sms_pass, context.getString(reasonCodeLabelRes(smsResult.reasonCode)))
                    } else {
                        context.getString(R.string.protection_test_sms_fail)
                    },
                recoveryHint = if (smsResult.isSpam) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        val pipelineTrace = repo.traceRules("+15555550123")
        val checkerErrors =
            pipelineTrace.entries.count {
                it.verdict == com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict.ERROR
            }
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_checker_health),
                passed = checkerErrors == 0,
                detail =
                    if (checkerErrors == 0) {
                        context.getString(
                            R.string.protection_test_checker_health_ok,
                            pipelineTrace.entries.size,
                        )
                    } else {
                        context.getString(
                            R.string.protection_test_checker_health_errors,
                            checkerErrors,
                            pipelineTrace.entries.size,
                        )
                    },
                priority = TestPriority.Required,
                recoveryHint = if (checkerErrors == 0) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        // Fixed samples at fixed hours. The old spam sample scored under the model's
        // threshold, so this check failed on every phone; these hold under the tree
        // model and its fallback (SpamMLFeatureContractTest).
        val mlResult =
            SpamMLScorer.isSpamAtHour(SpamMLScorer.ML_SPAM_CANARY, SpamMLScorer.ML_SPAM_CANARY_HOUR)
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_ml_spam_scorer),
                passed = mlResult,
                detail =
                    if (mlResult) {
                        context.getString(R.string.protection_test_ml_pass)
                    } else {
                        context.getString(R.string.protection_test_ml_fail)
                    },
                recoveryHint = if (mlResult) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        val mlClean =
            !SpamMLScorer.isSpamAtHour(SpamMLScorer.ML_CLEAN_CANARY, SpamMLScorer.ML_CLEAN_CANARY_HOUR)
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_ml_false_positive),
                passed = mlClean,
                detail =
                    if (mlClean) {
                        context.getString(R.string.protection_test_ml_fp_pass)
                    } else {
                        context.getString(R.string.protection_test_ml_fp_fail)
                    },
                recoveryHint = if (mlClean) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        val hotRangesLoaded =
            com.sysadmindoc.callshield.data.SpamHeuristics
                .hasHotRanges()
        val hotDataHealth = repo.readHotDataHealth()
        val hotDataNow = System.currentTimeMillis()
        val hotDataState = HotFeedFreshness.stateOf(hotDataHealth, hotDataNow)
        // An empty ranges feed the publisher cleared on purpose is a quiet day,
        // not missing protection.
        val hotRangesCleared = HotDataSync.HOT_RANGES_FEED in hotDataHealth.clearedFeeds
        val hotDataHealthy = hotDataState == HotFeedFreshness.State.CURRENT && (hotRangesLoaded || hotRangesCleared)
        // Downloads failing certificate verification are not a network hiccup:
        // this build's pins no longer match, and only an app update fixes that.
        val feedTrustFailed = repo.readFeedTrustFailedAt() > 0L
        if (feedTrustFailed) {
            results.add(
                TestResult(
                    name = context.getString(R.string.protection_test_feed_trust),
                    passed = false,
                    detail = context.getString(R.string.protection_test_feed_trust_fail),
                    recoveryHint = context.getString(R.string.protection_test_fix_update_app),
                ),
            )
        }
        results.add(
            TestResult(
                name = context.getString(R.string.protection_test_hot_list_data),
                passed = hotDataHealthy,
                detail =
                    when (hotDataState) {
                        HotFeedFreshness.State.UNREACHABLE -> {
                            hotDataHealthDetail(context, hotDataHealth.lastGoodTimestamp)
                        }

                        HotFeedFreshness.State.STALLED -> {
                            hotDataStalledDetail(context, hotDataHealth, hotDataNow)
                        }

                        HotFeedFreshness.State.REFUSED -> {
                            context.getString(R.string.protection_test_hot_refused)
                        }

                        HotFeedFreshness.State.CURRENT -> {
                            when {
                                hotRangesLoaded -> context.getString(R.string.protection_test_hot_pass)
                                hotRangesCleared -> context.getString(R.string.protection_test_hot_cleared)
                                else -> context.getString(R.string.protection_test_hot_fail)
                            }
                        }
                    },
                priority = TestPriority.Recommended,
                recoveryHint =
                    when {
                        hotDataHealthy -> null

                        // A stalled publisher is not something the device can
                        // sync its way out of, so do not tell the user to retry.
                        hotDataState == HotFeedFreshness.State.STALLED -> null

                        // Retrying a sync cannot get past a pin mismatch.
                        feedTrustFailed -> context.getString(R.string.protection_test_fix_update_app)

                        else -> context.getString(R.string.protection_test_fix_sync)
                    },
            ),
        )

        val callerNameSupport = repo.readCallerNameSupport()
        if (callerNameSupport == com.sysadmindoc.callshield.data.CallerNameSupport.NOT_PROVIDED) {
            results.add(
                TestResult(
                    name = context.getString(R.string.protection_test_caller_name),
                    passed = false,
                    detail = context.getString(R.string.protection_test_caller_name_unavailable),
                    priority = TestPriority.Informational,
                ),
            )
        }

        val activeCampaigns =
            com.sysadmindoc.callshield.data.CampaignDetector
                .getActiveCampaigns()
        results.add(
            TestResult(
                name = context.getString(R.string.test_campaign_detection),
                passed = true,
                detail = context.getString(R.string.test_campaign_monitoring, activeCampaigns.size),
                priority = TestPriority.Informational,
            ),
        )

        val mlScore =
            com.sysadmindoc.callshield.data.SpamMLScorer
                .score("+12025551234")
        val gbtActive = mlScore >= 0.0
        results.add(
            TestResult(
                name = context.getString(R.string.test_ml_model_loaded),
                passed = gbtActive,
                detail =
                    if (gbtActive) {
                        context.getString(R.string.test_ml_model_ready)
                    } else {
                        context.getString(R.string.test_ml_model_fallback)
                    },
                priority = TestPriority.Recommended,
                recoveryHint = if (gbtActive) null else context.getString(R.string.protection_test_fix_engine),
            ),
        )

        results.add(
            TestResult(
                name = context.getString(R.string.test_after_call_feedback),
                passed = true,
                detail = context.getString(R.string.test_feedback_ready),
                priority = TestPriority.Informational,
            ),
        )

        val securityPatch = Build.VERSION.SECURITY_PATCH
        val patchRecent =
            try {
                val parts = securityPatch.split("-").map { it.toInt() }
                if (parts.size == SECURITY_PATCH_PART_COUNT) {
                    val patchMillis =
                        java.util
                            .GregorianCalendar(
                                parts[0],
                                parts[1] - SECURITY_PATCH_MONTH_OFFSET,
                                parts[2],
                            ).timeInMillis
                    System.currentTimeMillis() - patchMillis < TimeUnit.DAYS.toMillis(SECURITY_PATCH_RECENT_DAYS)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        results.add(
            TestResult(
                name = context.getString(R.string.test_security_patch),
                passed = patchRecent,
                detail =
                    if (patchRecent) {
                        context.getString(R.string.test_security_patch_current, securityPatch)
                    } else {
                        context.getString(R.string.test_security_patch_stale, securityPatch)
                    },
                priority = TestPriority.Informational,
                recoveryHint = if (patchRecent) null else context.getString(R.string.test_security_patch_hint),
            ),
        )

        WorkerDiagnostics
            .read(context)
            .map { diagnostic -> workerDiagnosticResult(context, diagnostic) }
            .forEach(results::add)

        results
    }

private fun messageCapabilityTestResult(
    context: Context,
    status: MessageCapabilityStatus,
): TestResult {
    val uiState = messageCapabilityUiState(status)
    val nameRes =
        when (status.source) {
            MessageCapabilitySource.SMS_BROADCAST -> R.string.protection_test_sms_capability
            MessageCapabilitySource.NOTIFICATION_LISTENER -> R.string.protection_test_notification_capability
        }
    val detailRes =
        when (status.state) {
            MessageCapabilityState.NOT_OBSERVED -> {
                R.string.protection_test_capability_not_observed
            }

            MessageCapabilityState.FULL_CONTENT -> {
                if (status.smsOrderingAdvisory) {
                    R.string.protection_test_capability_full_sms_advisory
                } else {
                    R.string.protection_test_capability_full
                }
            }

            MessageCapabilityState.SENDER_ONLY -> {
                R.string.protection_test_capability_sender_only
            }

            MessageCapabilityState.BODY_REDACTED -> {
                R.string.protection_test_capability_redacted
            }

            MessageCapabilityState.DELAYED -> {
                R.string.protection_test_capability_delayed
            }

            MessageCapabilityState.UNSUPPORTED -> {
                R.string.protection_test_capability_unsupported
            }
        }
    return TestResult(
        name = context.getString(nameRes),
        passed = uiState.passed,
        detail = context.getString(detailRes),
        priority = if (status.isDegraded) TestPriority.Recommended else TestPriority.Informational,
        recoveryHint = if (status.isDegraded) context.getString(R.string.protection_test_capability_hint) else null,
    )
}

private fun hotDataStalledDetail(
    context: Context,
    health: HotDataHealth,
    now: Long,
): String {
    // STALLED is only ever derived from a parseable stamp, so one exists; the
    // fallback keeps a hand-edited store from crashing the screen.
    val oldest =
        health.feedGeneratedAt.values
            .map(HotFeedFreshness::publishedAtMillis)
            .filter { it > 0L }
            .minOrNull()
            ?: return hotDataHealthDetail(context, health.lastGoodTimestamp)
    val ageDays =
        (now - oldest)
            .coerceAtLeast(0L)
            .div(TimeUnit.DAYS.toMillis(1))
            .coerceAtLeast(1L)
    return context.getString(R.string.protection_test_hot_stalled, ageDays)
}

private fun hotDataHealthDetail(
    context: Context,
    lastGoodTimestamp: Long,
): String =
    if (lastGoodTimestamp > 0L) {
        val ageHours =
            (System.currentTimeMillis() - lastGoodTimestamp)
                .coerceAtLeast(0L)
                .div(TimeUnit.HOURS.toMillis(1))
                .coerceAtLeast(1L)
        context.getString(R.string.protection_test_hot_unavailable_age, ageHours)
    } else {
        context.getString(R.string.protection_test_hot_unavailable_never)
    }
