package com.sysadmindoc.callshield.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CallShieldScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onScreenCall(callDetails: Call.Details) {
        scope.launch {
            val repo = SpamRepository.getInstance(applicationContext)

            // Check if call blocking is enabled
            if (!repo.blockCallsEnabled.first()) {
                respondAllow(callDetails)
                return@launch
            }

            val handle = callDetails.handle
            val number = handle?.schemeSpecificPart ?: ""

            if (number.isEmpty()) {
                // Unknown/hidden number
                if (repo.blockUnknownEnabled.first()) {
                    respondBlock(callDetails, number, "hidden_number")
                } else {
                    respondAllow(callDetails)
                }
                return@launch
            }

            // Check STIR/SHAKEN verification (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && repo.stirShakenEnabled.first()) {
                val verstat = callDetails.callerNumberVerificationStatus
                if (verstat == Call.Details.VERIFICATION_STATUS_FAILED) {
                    respondBlock(callDetails, number, "stir_shaken_failed")
                    return@launch
                }
            }

            // Check against spam database
            val result = repo.isSpam(number)
            if (result.isSpam) {
                respondBlock(callDetails, number, result.matchSource)
            } else {
                respondAllow(callDetails)
            }
        }
    }

    private suspend fun respondBlock(callDetails: Call.Details, number: String, reason: String) {
        val repo = SpamRepository.getInstance(applicationContext)
        repo.logBlockedCall(number = number, isCall = true, matchReason = reason)

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
