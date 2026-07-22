package com.sysadmindoc.callshield.domain.repository

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.model.CallerIdentity

interface SpamCheckRepository {
    suspend fun checkSpam(
        number: String,
        smsBody: String?,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
        callerIdentity: CallerIdentity?,
    ): SpamCheckResult

    suspend fun checkSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
    ): SpamCheckResult
}
