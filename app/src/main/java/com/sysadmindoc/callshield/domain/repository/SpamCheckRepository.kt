package com.sysadmindoc.callshield.domain.repository

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

interface SpamCheckRepository {
    suspend fun checkSpam(
        number: String,
        smsBody: String?,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
        verificationStatus: Int?,
    ): SpamCheckResult

    suspend fun checkSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
    ): SpamCheckResult
}
