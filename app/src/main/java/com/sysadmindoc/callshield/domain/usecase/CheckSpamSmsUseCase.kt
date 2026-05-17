package com.sysadmindoc.callshield.domain.usecase

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.SpamCheckResult
import com.sysadmindoc.callshield.data.SpamRepository

class CheckSpamSmsUseCase(
    private val repository: SpamRepository,
) {
    suspend operator fun invoke(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult =
        repository.isSpamSms(
            number = number,
            body = body,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
        )
}
