package com.sysadmindoc.callshield.domain.repository

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

interface SpamCheckRepository {
    suspend fun checkSpam(
        number: String,
        smsBody: String?,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
        callerIdentity: CallerIdentity?,
    ): SpamCheckResult

    /** [subscriptionId] is the SIM subscription a live text arrived on, when the broadcast named one. */
    suspend fun checkSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
        subscriptionId: Int? = null,
    ): SpamCheckResult

    /**
     * A live message the SMS inbox never holds (RCS, a chat app's notification).
     * Whether it's someone's first text can't be read from the inbox, so it's
     * never treated as one.
     */
    suspend fun checkSpamMessageOutsideInbox(
        number: String,
        body: String,
        prefsSnapshot: Preferences?,
    ): SpamCheckResult = checkSpamSms(number, body, realtimeCall = true, prefsSnapshot = prefsSnapshot)
}
