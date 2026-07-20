package com.sysadmindoc.callshield.domain.usecase

import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.repository.BlocklistRepository
import javax.inject.Inject

@Suppress("TooManyFunctions")
class ManageBlocklistUseCase
    @Inject
    constructor(
        private val repository: BlocklistRepository,
    ) {
        suspend fun blockNumber(
            number: String,
            type: String = "unknown",
            description: String = "",
        ) = repository.blockNumber(number, type, description)

        suspend fun temporaryBlockNumber(
            number: String,
            expiresAt: Long,
            type: String = "unknown",
            description: String = "",
        ) = repository.temporaryBlockNumber(number, expiresAt, type, description)

        suspend fun temporaryAllowNumber(
            number: String,
            expiresAt: Long,
            description: String = "",
        ) = repository.temporaryAllowNumber(number, expiresAt, description)

        suspend fun unblockNumber(number: SpamNumber) = repository.unblockNumber(number)

        suspend fun addWildcardRule(
            pattern: String,
            isRegex: Boolean = false,
            description: String = "",
            schedule: TimeSchedule = TimeSchedule(),
        ) = repository.addWildcardRule(pattern, isRegex, description, schedule)

        suspend fun deleteWildcardRule(rule: WildcardRule) = repository.deleteWildcardRule(rule)

        suspend fun toggleWildcardRule(
            id: Long,
            enabled: Boolean,
        ) = repository.toggleWildcardRule(id, enabled)

        suspend fun addHashWildcardRule(
            pattern: String,
            description: String = "",
            schedule: TimeSchedule = TimeSchedule(),
        ): Boolean = repository.addHashWildcardRule(pattern, description, schedule)

        suspend fun deleteHashWildcardRule(rule: HashWildcardRule) = repository.deleteHashWildcardRule(rule)

        suspend fun toggleHashWildcardRule(
            id: Long,
            enabled: Boolean,
        ) = repository.toggleHashWildcardRule(id, enabled)

        suspend fun addKeywordRule(
            keyword: String,
            caseSensitive: Boolean = false,
            description: String = "",
            schedule: TimeSchedule = TimeSchedule(),
        ) = repository.addKeywordRule(keyword, caseSensitive, description, schedule)

        suspend fun deleteKeywordRule(rule: SmsKeywordRule) = repository.deleteKeywordRule(rule)

        suspend fun toggleKeywordRule(
            id: Long,
            enabled: Boolean,
        ) = repository.toggleKeywordRule(id, enabled)

        suspend fun addToWhitelist(
            number: String,
            description: String = "",
            isEmergency: Boolean = false,
        ) = repository.addToWhitelist(number, description, isEmergency)

        suspend fun removeFromWhitelist(entry: WhitelistEntry) = repository.removeFromWhitelist(entry)

        suspend fun setWhitelistEmergency(
            id: Long,
            emergency: Boolean,
        ) = repository.setWhitelistEmergency(id, emergency)
    }
