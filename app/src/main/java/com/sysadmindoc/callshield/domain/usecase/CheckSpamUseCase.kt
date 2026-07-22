package com.sysadmindoc.callshield.domain.usecase

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.repository.SpamCheckRepository

class CheckSpamUseCase(
    private val repository: SpamCheckRepository,
) {
    suspend operator fun invoke(
        number: String,
        smsBody: String? = null,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
        callerIdentity: CallerIdentity? = null,
    ): SpamCheckResult =
        repository.checkSpam(
            number = number,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
            callerIdentity = callerIdentity,
        )
}
