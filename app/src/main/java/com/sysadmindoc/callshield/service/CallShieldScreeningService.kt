package com.sysadmindoc.callshield.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallShieldScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Run on the process-wide appScope instead of a service-scoped one.
        // CallScreeningService is frequently unbound moments after we reply,
        // and a service-scoped coroutine could be cancelled mid-decision —
        // leaving Android to auto-allow the call once the 5-second window
        // elapses. appScope survives the unbind so the response and the
        // subsequent logging/notification always run to completion.
        CallShieldApp.appScope.launch(Dispatchers.IO) {
            val appContext = applicationContext
            try {
                val repo = SpamRepository.getInstance(appContext)
                // One snapshot of all prefs — the 5-second deadline is tight
                // and individual Flow.first() calls each spin up a collector.
                val prefs = repo.readPrefsSnapshot()

                if (!(prefs[SpamRepository.KEY_BLOCK_CALLS] ?: true)) {
                    respondAllow(callDetails)
                    return@launch
                }

                val handle = callDetails.handle
                val number = handle?.schemeSpecificPart ?: ""

                if (number.isEmpty()) {
                    if (prefs[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false) {
                        respondBlock(callDetails, number, "hidden_number", prefs = prefs)
                    } else {
                        respondAllow(callDetails)
                    }
                    return@launch
                }

                // Contact whitelist — cached inside SpamHeuristics so this stays cheap.
                // Fast-path shortcut before we run the pipeline: a contact never
                // needs any of the 13 downstream checks, and skipping them saves
                // tens of milliseconds against the 5 s deadline.
                if ((prefs[SpamRepository.KEY_CONTACT_WHITELIST] ?: true) &&
                    SpamHeuristics.isInContacts(appContext, number)
                ) {
                    respondAllow(callDetails)
                    return@launch
                }

                // STIR/SHAKEN now lives in the pipeline as StirShakenChecker so a
                // MANUAL_WHITELIST or CONTACT_WHITELIST entry can override it. We
                // just forward the verification status through the pipeline.
                val verificationStatus: Int? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        callDetails.callerNumberVerificationStatus
                    } else null

                // Full spam check — reuses the snapshot so we don't re-read DataStore.
                val result = repo.isSpam(
                    number = number,
                    prefsSnapshot = prefs,
                    verificationStatus = verificationStatus,
                )
                if (result.isSpam) {
                    respondBlock(
                        callDetails = callDetails,
                        number = number,
                        reason = result.matchSource,
                        confidence = result.confidence,
                        prefs = prefs
                    )
                } else {
                    // Unknown non-contact caller — area-code-only caller ID overlay
                    val location = AreaCodeLookup.lookup(number)
                    if (location != null) {
                        try {
                            val intent = android.content.Intent(appContext, CallerIdOverlayService::class.java).apply {
                                putExtra("number", number)
                                putExtra("confidence", 0)
                                putExtra("reason", location)
                            }
                            appContext.startService(intent)
                        } catch (_: Exception) {}
                    }
                    respondAllow(callDetails)

                    // After-call feedback notification, deferred. Must run on
                    // appScope since the service is typically unbound by the
                    // time 10 s has passed. Contact status is re-checked at
                    // post-time in case the user just added the caller.
                    CallShieldApp.appScope.launch {
                        delay(10_000L)
                        if (!SpamHeuristics.isInContacts(appContext, number)) {
                            NotificationHelper.notifyAfterCall(appContext, number)
                        }
                    }
                }
            } catch (_: Exception) {
                // Guarantee a response even on error — fail-open (allow call through)
                try { respondAllow(callDetails) } catch (_: Exception) {}
            }
        }
    }

    private fun respondBlock(
        callDetails: Call.Details,
        number: String,
        reason: String,
        confidence: Int = 100,
        prefs: androidx.datastore.preferences.core.Preferences,
    ) {
        // Respond FIRST so Android always sees a decision before the 5-second
        // deadline, regardless of whether the Room insert is quick or slow.
        // The log/notification run async on appScope — losing them is better
        // than losing the block decision itself.
        val response = buildBlockResponse(prefs, confidence)
        respondToCall(callDetails, response)

        CallShieldApp.appScope.launch {
            try {
                SpamRepository.getInstance(applicationContext)
                    .logBlockedCall(number = number, isCall = true, matchReason = reason, confidence = confidence)
            } catch (_: Exception) { }
        }
    }

    /**
     * Decision table for how a block is delivered to the telecom stack.
     *
     * Three shapes, priority descending:
     *   1. `KEY_SILENT_VOICEMAIL` on → user asked for every block to be
     *      silenced (no ring, lands in voicemail). Wins outright — the
     *      user preference is unambiguous.
     *   2. `KEY_AUTOMUTE_LOW_CONFIDENCE` on AND confidence < 60 → the
     *      detection layer isn't fully certain (heuristic at the
     *      threshold, ML below 60%, campaign-burst hit), so silence
     *      instead of hard-reject. Lets the user inspect the log entry
     *      later without the interruption. Numbers v1.7.0-onward lifted
     *      from adamff-dev/spam-call-blocker-app's "auto-mute" mode.
     *   3. Default → hard reject with setDisallowCall + setRejectCall.
     *      Both flags set for maximum compatibility across OEMs — some
     *      carriers ignore one but not the other.
     *
     * All three paths keep setSkipCallLog=false + setSkipNotification=false
     * so the block is still visible to the user.
     */
    private fun buildBlockResponse(
        prefs: androidx.datastore.preferences.core.Preferences,
        confidence: Int,
    ): CallResponse {
        val silentVoicemail = prefs[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false
        val autoMuteLowConf = prefs[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false
        return if (shouldSilence(silentVoicemail, autoMuteLowConf, confidence)) {
            CallResponse.Builder()
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        } else {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        }
    }

    companion object {
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
        ): Boolean =
            silentVoicemailEnabled ||
                (autoMuteLowConfidenceEnabled && confidence < AUTO_MUTE_CONFIDENCE_THRESHOLD)
    }

    private fun respondAllow(callDetails: Call.Details) {
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .build()
        respondToCall(callDetails, response)
    }
}
