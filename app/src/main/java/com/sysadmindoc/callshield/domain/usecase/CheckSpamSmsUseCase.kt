package com.sysadmindoc.callshield.domain.usecase

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.repository.SpamCheckRepository

class CheckSpamSmsUseCase(
    private val repository: SpamCheckRepository,
) {
    suspend operator fun invoke(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult =
        repository.checkSpamSms(
            number = number,
            body = body,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
        )
}
