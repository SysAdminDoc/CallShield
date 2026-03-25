package com.sysadmindoc.callshield.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CallShieldScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onScreenCall(callDetails: Call.Details) {
        scope.launch {
            try {
                val repo = SpamRepository.getInstance(applicationContext)

                if (!repo.blockCallsEnabled.first()) {
                    respondAllow(callDetails)
                    return@launch
                }

                val handle = callDetails.handle
                val number = handle?.schemeSpecificPart ?: ""

                if (number.isEmpty()) {
                    if (repo.blockUnknownEnabled.first()) {
                        respondBlock(callDetails, number, "hidden_number")
                    } else {
                        respondAllow(callDetails)
                    }
                    return@launch
                }

                // Contact whitelist
                if (repo.contactWhitelistEnabled.first() &&
                    SpamHeuristics.isInContacts(applicationContext, number)) {
                    respondAllow(callDetails)
                    return@launch
                }

                // STIR/SHAKEN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && repo.stirShakenEnabled.first()) {
                    val verstat = callDetails.callerNumberVerificationStatus
                    @Suppress("DEPRECATION")
                    if (verstat == android.telecom.Connection.VERIFICATION_STATUS_FAILED) {
                        respondBlock(callDetails, number, "stir_shaken_failed")
                        return@launch
                    }
                }

                // Full spam check (database + wildcards + time + frequency + heuristics + overlay)
                val result = repo.isSpam(number)
                if (result.isSpam) {
                    respondBlock(callDetails, number, result.matchSource, result.confidence)
                } else {
                    if (!SpamHeuristics.isInContacts(applicationContext, number)) {
                        // Show caller ID info overlay for unknown numbers
                        val location = com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup.lookup(number)
                        if (location != null) {
                            try {
                                val intent = android.content.Intent(applicationContext, CallerIdOverlayService::class.java).apply {
                                    putExtra("number", number)
                                    putExtra("confidence", 0)
                                    putExtra("reason", location)
                                }
                                applicationContext.startService(intent)
                            } catch (_: Exception) {}
                        }
                        repo.promptSpamRating(number)
                    }
                    respondAllow(callDetails)
                }
            } catch (_: Exception) {
                // Guarantee a response even on error — fail-open (allow call through)
                try { respondAllow(callDetails) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun respondBlock(
        callDetails: Call.Details, number: String,
        reason: String, confidence: Int = 100
    ) {
        val repo = SpamRepository.getInstance(applicationContext)
        repo.logBlockedCall(number = number, isCall = true, matchReason = reason, confidence = confidence)

        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }

    private fun respondAllow(callDetails: Call.Details) {
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .build()
        respondToCall(callDetails, response)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
