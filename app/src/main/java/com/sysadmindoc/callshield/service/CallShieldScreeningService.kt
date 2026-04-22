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
        val silent = prefs[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false
        val response = if (silent) {
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
        respondToCall(callDetails, response)

        CallShieldApp.appScope.launch {
            try {
                SpamRepository.getInstance(applicationContext)
                    .logBlockedCall(number = number, isCall = true, matchReason = reason, confidence = confidence)
            } catch (_: Exception) { }
        }
    }

    private fun respondAllow(callDetails: Call.Details) {
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .build()
        respondToCall(callDetails, response)
    }
}
