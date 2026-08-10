package com.sysadmindoc.callshield.service

import android.os.Build
import android.os.UserManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.CategoryCallPolicy
import com.sysadmindoc.callshield.data.ContactGroupCatalog
import com.sysadmindoc.callshield.data.OutgoingRiskPolicy
import com.sysadmindoc.callshield.data.OutgoingRiskWarning
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.di.ApplicationScope
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.usecase.CheckSpamUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class CallShieldScreeningService : CallScreeningService() {
    // Tests can assign these overrides directly. Production resolves the Hilt
    // providers only after unlock so Room/DataStore are never constructed from
    // a direct-boot callback.
    lateinit var repo: SpamRepository

    lateinit var checkSpam: CheckSpamUseCase

    lateinit var spamHeuristics: SpamHeuristics

    @Inject
    lateinit var repoProvider: Provider<SpamRepository>

    @Inject
    lateinit var checkSpamProvider: Provider<CheckSpamUseCase>

    @Inject
    lateinit var spamHeuristicsProvider: Provider<SpamHeuristics>

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    internal var outgoingWarningLauncher: (android.content.Context, OutgoingRiskWarning) -> Unit =
        { context, warning ->
            context.startService(CallerIdOverlayService.outgoingRiskIntent(context, warning))
        }

    override fun onScreenCall(callDetails: Call.Details) {
        val responseGate =
            ScreeningResponseGate<CallResponse> { response -> respondToCall(callDetails, response) }
        val userManager = getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            handleDirectBootCall(callDetails, responseGate)
            return
        }

        // Android dispatches both incoming and outgoing calls to the active
        // screening service. Outgoing warnings use a separate, local-only
        // lookup so the inbound blocker can never log, analyze contacts, send
        // feedback, or respond to an outgoing call.
        when (callDetails.callDirection) {
            Call.Details.DIRECTION_OUTGOING -> {
                handleOutgoingCall(callDetails)
                return
            }

            Call.Details.DIRECTION_INCOMING -> {
                Unit
            }

            else -> {
                // DIRECTION_UNKNOWN: some OEM stacks deliver screened incoming
                // calls without a direction. Fail open with an explicit allow —
                // returning without responding would make Android hold the
                // screening slot until its timeout and delay ringing.
                respondAllow(responseGate)
                return
            }
        }

        // Run on the process-wide appScope instead of a service-scoped one.
        // CallScreeningService is frequently unbound moments after we reply,
        // and a service-scoped coroutine could be cancelled mid-decision —
        // leaving Android to auto-allow the call once the 5-second window
        // elapses. appScope survives the unbind so the response and the
        // subsequent logging/notification always run to completion.
        applicationScope.launch {
            val appContext = applicationContext
            // The checker pipeline has its own budget accounting, but provider
            // creation, Room startup, and an individual DAO call can still
            // stall before a checker gets a chance to observe that budget.
            // Keep a 500 ms response buffer for Telecom's five-second window.
            try {
                withTimeoutOrNull(SCREENING_TIMEOUT_MS) {
                    try {
                // Resolve injected providers inside the fail-open boundary. Room,
                // DataStore, or a Hilt component can fail during lazy creation;
                // resolving them before this try block would crash the coroutine
                // without ever sending telecom an explicit response.
                val repository = repository()
                val heuristics = heuristics()

                // One snapshot of all prefs — the 5-second deadline is tight
                // and individual Flow.first() calls each spin up a collector.
                val prefs = repository.readPrefsSnapshot()

                if (!(prefs[SpamRepository.KEY_BLOCK_CALLS] ?: true)) {
                    respondAllow(responseGate)
                    return@withTimeoutOrNull
                }

                val handle = callDetails.handle
                val number = repository.normalizeNumber(handle?.schemeSpecificPart.orEmpty())

                if (number.isEmpty()) {
                    if (prefs[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false) {
                        respondBlock(callDetails, responseGate, number, "hidden_number", prefs = prefs)
                    } else {
                        respondAllow(responseGate)
                    }
                    return@withTimeoutOrNull
                }

                // Contact whitelist — cached inside SpamHeuristics so this stays cheap.
                // Fast-path shortcut before we run the pipeline: a contact never
                // needs any of the 13 downstream checks, and skipping them saves
                // tens of milliseconds against the 5 s deadline.
                if ((prefs[SpamRepository.KEY_CONTACT_WHITELIST] ?: true) &&
                    heuristics.isInContacts(
                        appContext,
                        number,
                        prefs[SpamRepository.KEY_SELECTED_CONTACT_GROUPS]
                            ?.let(ContactGroupCatalog::preserveScope),
                    )
                ) {
                    respondAllow(responseGate)
                    return@withTimeoutOrNull
                }

                // STIR/SHAKEN now lives in the pipeline as StirShakenChecker so a
                // MANUAL_WHITELIST or CONTACT_WHITELIST entry can override it. We
                // just forward the verification status through the pipeline.
                val verificationStatus: Int? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        callDetails.callerNumberVerificationStatus
                    } else {
                        null
                    }
                val callerName =
                    callDetails.callerDisplayName?.takeIf {
                        callDetails.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED &&
                            it.isNotBlank()
                    }

                // Full spam check — reuses the snapshot so we don't re-read DataStore.
                val result =
                    spamChecker()(
                        number = number,
                        prefsSnapshot = prefs,
                        callerIdentity = CallerIdentity(verificationStatus, callerName),
                    )
                if (result.isSpam) {
                    val categoryAction =
                        CategoryCallPolicy.parseMatchSource(result.matchSource)?.action
                            ?: CategoryCallAction.INHERIT
                    if (categoryAction == CategoryCallAction.ALLOW) {
                        respondAllow(responseGate)
                    } else {
                        respondBlock(
                            callDetails = callDetails,
                            responseGate = responseGate,
                            number = number,
                            reason = result.matchSource,
                            confidence = result.confidence,
                            prefs = prefs,
                        )
                    }
                } else {
                    val repeatedUrgentAllow = result.matchSource == "repeated_urgent"
                    val suppressFeedback = shouldSuppressAfterCallFeedback(result.matchSource)
                    // Unknown non-contact caller — area-code-only caller ID overlay
                    val location = AreaCodeLookup.lookup(number)
                    if (location != null) {
                        try {
                            val intent =
                                android.content.Intent(appContext, CallerIdOverlayService::class.java).apply {
                                    putExtra("number", number)
                                    putExtra("confidence", 0)
                                    putExtra("reason", location)
                                }
                            appContext.startService(intent)
                        } catch (_: Exception) {
                        }
                    }
                    respondAllow(responseGate)

                    if (repeatedUrgentAllow) {
                        NotificationHelper.notifyRepeatedUrgentAllowed(appContext, number)
                    } else if (!suppressFeedback) {
                        // After-call feedback notification, deferred. Must run on
                        // appScope since the service is typically unbound by the
                        // time 10 s has passed. Contact status is re-checked at
                        // post-time in case the user just added the caller.
                        applicationScope.launch {
                            try {
                                if (waitForFeedbackWindow(appContext) &&
                                    !heuristics.isInContacts(
                                        appContext,
                                        number,
                                        prefs[SpamRepository.KEY_SELECTED_CONTACT_GROUPS]
                                            ?.let(ContactGroupCatalog::preserveScope),
                                    )
                                ) {
                                    NotificationHelper.notifyAfterCall(appContext, number)
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                Log.w(TAG, "After-call feedback failed", failure)
                            }
                        }
                    }
                }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (e: Exception) {
                        // Guarantee a response even on error — fail-open (allow call through).
                        try {
                            respondAllow(responseGate)
                        } catch (_: Exception) {
                        }
                        // A corrupt on-disk database would otherwise fail every DAO
                        // call and leave the screener permanently fail-open with no
                        // signal. Detect that specific case, rebuild a clean DB, and
                        // re-sync so protection self-heals on the next call.
                        if (AppDatabase.isCorruptionException(e)) {
                            try {
                                if (AppDatabase.recoverFromCorruption(appContext)) {
                                    Log.w(TAG, "Recovered from corrupt database; re-syncing spam data")
                                    SyncWorker.syncNow(appContext)
                                    HotListSyncWorker.schedule(appContext)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                    ?: respondAllow(responseGate)
            } catch (failure: CancellationException) {
                // Even a service-scope cancellation must leave Telecom with an
                // explicit response; otherwise it waits until its hard timeout.
                withContext(NonCancellable) {
                    respondAllow(responseGate)
                }
            }
        }
    }

    private fun handleOutgoingCall(callDetails: Call.Details) {
        applicationScope.launch {
            try {
                val repository = repository()
                val prefs = repository.readPrefsSnapshot()
                if (!(prefs[SpamRepository.KEY_OUTGOING_RISK_WARNING] ?: false)) return@launch

                val warning =
                    OutgoingRiskPolicy.evaluate(
                        repository = repository,
                        rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty(),
                    ) ?: return@launch
                outgoingWarningLauncher(applicationContext, warning)
            } catch (_: IOException) {
                // Optional warning only; outgoing calls must never be delayed or altered.
            } catch (_: RuntimeException) {
                // Room and overlay failures are best-effort and never affect the call.
            }
        }
    }

    private suspend fun respondBlock(
        callDetails: Call.Details,
        responseGate: ScreeningResponseGate<CallResponse>,
        number: String,
        reason: String,
        confidence: Int = 100,
        prefs: androidx.datastore.preferences.core.Preferences,
    ) {
        val repository = repository()
        val logTimestamp = System.currentTimeMillis()
        val logKey = blockedCallLogKey(callDetails, number, reason, confidence, logTimestamp)
        var pendingLogQueued = false
        try {
            repository.enqueuePendingBlockedCallLog(
                idempotencyKey = logKey,
                number = number,
                isCall = true,
                matchReason = reason,
                confidence = confidence,
                timestamp = logTimestamp,
            )
            pendingLogQueued = true
        } catch (_: Exception) {
            // The block decision is still more important than logging. If the
            // queue write failed, the async fallback below makes a best effort.
        }

        val categoryAction =
            CategoryCallPolicy.parseMatchSource(reason)?.action
                ?: CategoryCallAction.INHERIT
        val response = buildBlockResponse(prefs, confidence, categoryAction)
        responseGate.respond(response)

        applicationScope.launch {
            try {
                if (pendingLogQueued) {
                    repository.flushPendingBlockedCallLogs()
                } else {
                    repository.logBlockedCall(
                        number = number,
                        isCall = true,
                        matchReason = reason,
                        confidence = confidence,
                        timestamp = logTimestamp,
                        logKey = logKey,
                    )
                }
            } catch (_: Exception) {
                // The queued row survives process death; a worker retry will
                // pick it up on the next run.
            } finally {
                PendingBlockedCallLogWorker.schedule(applicationContext)
            }
        }
    }

    /**
     * Decision table for how a block is delivered to the telecom stack.
     *
     * Delivery priority, descending:
     *   1. A per-category BLOCK or SILENCE action overrides global delivery.
     *      ALLOW is handled before this response is built. Manual/emergency
     *      whitelists and explicit personal blocks are resolved before the
     *      category policy, so category choices cannot weaken those rules.
     *   2. `KEY_SILENT_VOICEMAIL` on → user asked for every inherited block to be
     *      silenced (no ring, lands in voicemail). Wins outright — the
     *      user preference is unambiguous.
     *   3. `KEY_AUTOMUTE_LOW_CONFIDENCE` on AND confidence < 60 → the
     *      detection layer isn't fully certain (heuristic at the
     *      threshold, ML below 60%, campaign-burst hit), so silence
     *      instead of hard-reject. Lets the user inspect the log entry
     *      later without the interruption. Numbers v1.7.0-onward lifted
     *      from adamff-dev/spam-call-blocker-app's "auto-mute" mode.
     *   4. Default → hard reject with setDisallowCall + setRejectCall.
     *      Both flags set for maximum compatibility across OEMs — some
     *      carriers ignore one but not the other.
     *
     * All three paths keep setSkipCallLog=false + setSkipNotification=false
     * so the block is still visible to the user.
     */
    private fun buildBlockResponse(
        prefs: androidx.datastore.preferences.core.Preferences,
        confidence: Int,
        categoryAction: CategoryCallAction,
    ): CallResponse {
        val silentVoicemail = prefs[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false
        val autoMuteLowConf = prefs[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false
        return if (shouldSilence(silentVoicemail, autoMuteLowConf, confidence, categoryAction)) {
            CallResponse
                .Builder()
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        } else {
            CallResponse
                .Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        }
    }

    private fun handleDirectBootCall(
        callDetails: Call.Details,
        responseGate: ScreeningResponseGate<CallResponse>,
    ) {
        when (callDetails.callDirection) {
            Call.Details.DIRECTION_OUTGOING -> {
                return
            }

            Call.Details.DIRECTION_INCOMING -> {
                Unit
            }

            else -> {
                respondAllow(responseGate)
                return
            }
        }
        try {
            val snapshot = DirectBootScreeningStore.read(applicationContext)
            if (!snapshot.ready || !snapshot.blockCallsEnabled) {
                respondAllow(responseGate)
                return
            }

            val number =
                PhoneIdentityCanonicalizer
                    .cachedFromContext(applicationContext)
                    .canonicalizePhone(callDetails.handle?.schemeSpecificPart.orEmpty())
            val shouldBlock =
                if (number.isBlank()) {
                    snapshot.blockUnknownEnabled
                } else {
                    snapshot.isBlocked(number)
                }
            if (!shouldBlock) {
                respondAllow(responseGate)
                return
            }

            val response =
                if (snapshot.silentVoicemailEnabled) {
                    CallResponse
                        .Builder()
                        .setSilenceCall(true)
                        .setSkipCallLog(false)
                        .setSkipNotification(false)
                        .build()
                } else {
                    CallResponse
                        .Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipCallLog(false)
                        .setSkipNotification(false)
                        .build()
                }
            responseGate.respond(response)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Direct-boot screening failed open", exception)
            respondAllow(responseGate)
        }
    }

    private fun repository(): SpamRepository = if (::repo.isInitialized) repo else repoProvider.get()

    private suspend fun waitForFeedbackWindow(context: android.content.Context): Boolean {
        delay(AFTER_CALL_FEEDBACK_DELAY_MS)
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val telecomState =
            try {
                context.getSystemService(android.telephony.TelephonyManager::class.java)
            } catch (_: RuntimeException) {
                null
            } ?: return true

        return withTimeoutOrNull(AFTER_CALL_MAX_WAIT_MS) {
            @Suppress("DEPRECATION")
            while (shouldPostFeedbackForCallState(canReadPhoneState = true, callState = telecomState.callState).not()) {
                delay(AFTER_CALL_STATE_POLL_MS)
            }
            true
        } ?: false
    }

    private fun spamChecker(): CheckSpamUseCase = if (::checkSpam.isInitialized) checkSpam else checkSpamProvider.get()

    private fun heuristics(): SpamHeuristics = if (::spamHeuristics.isInitialized) spamHeuristics else spamHeuristicsProvider.get()

    companion object {
        private const val TAG = "CallShieldScreening"
        private const val SCREENING_TIMEOUT_MS = 4_500L
        private const val AFTER_CALL_FEEDBACK_DELAY_MS = 10_000L
        private const val AFTER_CALL_STATE_POLL_MS = 1_000L
        private const val AFTER_CALL_MAX_WAIT_MS = 4L * 60L * 60L * 1000L

        internal fun shouldPostFeedbackForCallState(
            canReadPhoneState: Boolean,
            callState: Int,
        ): Boolean =
            !canReadPhoneState ||
                callState == android.telephony.TelephonyManager.CALL_STATE_IDLE

        // Blocks at or above this confidence are always hard-rejected even
        // with auto-mute on — a database hit, user blocklist, STIR fail, or
        // high-scoring heuristic is certain enough that the user shouldn't
        // have to fish it out of voicemail.
        internal const val AUTO_MUTE_CONFIDENCE_THRESHOLD = 60

        /**
         * Pure decision: should a block arrive as a silent voicemail
         * drop (true) or as a hard reject (false)?
         *
         * - `silentVoicemailEnabled` wins unconditionally when on.
         * - A category SILENCE or BLOCK action overrides the global delivery
         *   mode. Category ALLOW is handled before a block response is built.
         * - Otherwise, `autoMuteLowConfidenceEnabled` silences only blocks
         *   with `confidence < AUTO_MUTE_CONFIDENCE_THRESHOLD`.
         * - Default is hard reject.
         *
         * Kept as a companion function so unit tests can cover every
         * combination without standing up a CallScreeningService (which
         * requires a bound telecom context and an Android runtime).
         */
        internal fun shouldSilence(
            silentVoicemailEnabled: Boolean,
            autoMuteLowConfidenceEnabled: Boolean,
            confidence: Int,
            categoryAction: CategoryCallAction = CategoryCallAction.INHERIT,
        ): Boolean =
            when (categoryAction) {
                CategoryCallAction.SILENCE -> {
                    true
                }

                CategoryCallAction.BLOCK -> {
                    false
                }

                CategoryCallAction.INHERIT,
                CategoryCallAction.ALLOW,
                -> {
                    silentVoicemailEnabled ||
                        (autoMuteLowConfidenceEnabled && confidence < AUTO_MUTE_CONFIDENCE_THRESHOLD)
                }
            }

        fun shouldSuppressAfterCallFeedback(matchSource: String): Boolean = matchSource == "emergency_callback"
    }

    private fun blockedCallLogKey(
        callDetails: Call.Details,
        number: String,
        reason: String,
        confidence: Int,
        fallbackTimestamp: Long,
    ): String {
        val callTimestamp = callDetails.creationTimeMillis.takeIf { it > 0 } ?: fallbackTimestamp
        val caller = number.ifBlank { "hidden" }
        return "call:$callTimestamp:$caller:$reason:$confidence"
    }

    private fun respondAllow(responseGate: ScreeningResponseGate<CallResponse>) {
        val response =
            CallResponse
                .Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .build()
        responseGate.respond(response)
    }
}
