package com.sysadmindoc.callshield.domain.usecase

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

class CheckSpamUseCase(
    private val repository: SpamRepository,
) {
    suspend operator fun invoke(
        number: String,
        smsBody: String? = null,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
        verificationStatus: Int? = null,
    ): SpamCheckResult =
        repository.isSpam(
            number = number,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
            verificationStatus = verificationStatus,
        )
}
