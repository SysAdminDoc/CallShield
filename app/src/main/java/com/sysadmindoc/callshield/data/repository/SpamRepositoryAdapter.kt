package com.sysadmindoc.callshield.data.repository

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.domain.repository.SpamCheckRepository
import com.sysadmindoc.callshield.domain.repository.BlocklistRepository as DomainBlocklistRepository
import com.sysadmindoc.callshield.domain.repository.SyncRepository as DomainSyncRepository

@Suppress("TooManyFunctions")
class SpamRepositoryAdapter(
    private val repository: SpamRepository,
) : SpamCheckRepository,
    DomainSyncRepository,
    DomainBlocklistRepository {
    override suspend fun checkSpam(
        number: String,
        smsBody: String?,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
        callerIdentity: CallerIdentity?,
    ): SpamCheckResult =
        repository.isSpam(
            number = number,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
            callerIdentity = callerIdentity,
        )

    override suspend fun checkSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean,
        prefsSnapshot: Preferences?,
    ): SpamCheckResult =
        repository.isSpamSms(
            number = number,
            body = body,
            realtimeCall = realtimeCall,
            prefsSnapshot = prefsSnapshot,
        )

    override suspend fun syncDatabase(force: Boolean): SyncResult = repository.syncFromGitHub(force = force)

    override suspend fun blockNumber(
        number: String,
        type: String,
        description: String,
    ) {
        repository.blockNumber(number, type, description)
    }

    override suspend fun temporaryBlockNumber(
        number: String,
        expiresAt: Long,
        type: String,
        description: String,
    ) {
        repository.temporaryBlockNumber(number, expiresAt, type, description)
    }

    override suspend fun temporaryAllowNumber(
        number: String,
        expiresAt: Long,
        description: String,
    ) {
        repository.temporaryAllowNumber(number, expiresAt, description)
    }

    override suspend fun unblockNumber(number: SpamNumber) {
        repository.unblockNumber(number)
    }

    override suspend fun addWildcardRule(
        pattern: String,
        isRegex: Boolean,
        description: String,
        schedule: TimeSchedule,
    ) {
        repository.addWildcardRule(pattern, isRegex, description, schedule)
    }

    override suspend fun deleteWildcardRule(rule: WildcardRule) {
        repository.deleteWildcardRule(rule)
    }

    override suspend fun toggleWildcardRule(
        id: Long,
        enabled: Boolean,
    ) {
        repository.toggleWildcardRule(id, enabled)
    }

    override suspend fun addHashWildcardRule(
        pattern: String,
        description: String,
        schedule: TimeSchedule,
    ): Boolean = repository.addHashWildcardRule(pattern, description, schedule)

    override suspend fun deleteHashWildcardRule(rule: HashWildcardRule) {
        repository.deleteHashWildcardRule(rule)
    }

    override suspend fun toggleHashWildcardRule(
        id: Long,
        enabled: Boolean,
    ) {
        repository.toggleHashWildcardRule(id, enabled)
    }

    override suspend fun addKeywordRule(
        keyword: String,
        caseSensitive: Boolean,
        description: String,
        schedule: TimeSchedule,
    ) {
        repository.addKeywordRule(keyword, caseSensitive, description, schedule)
    }

    override suspend fun deleteKeywordRule(rule: SmsKeywordRule) {
        repository.deleteKeywordRule(rule)
    }

    override suspend fun toggleKeywordRule(
        id: Long,
        enabled: Boolean,
    ) {
        repository.toggleKeywordRule(id, enabled)
    }

    override suspend fun addToWhitelist(
        number: String,
        description: String,
        isEmergency: Boolean,
    ) {
        repository.addToWhitelist(number, description, isEmergency)
    }

    override suspend fun removeFromWhitelist(entry: WhitelistEntry) {
        repository.removeFromWhitelist(entry)
    }

    override suspend fun setWhitelistEmergency(
        id: Long,
        emergency: Boolean,
    ) {
        repository.setWhitelistEmergency(id, emergency)
    }
}
