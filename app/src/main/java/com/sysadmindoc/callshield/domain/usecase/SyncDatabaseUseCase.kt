package com.sysadmindoc.callshield.domain.usecase

import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.domain.model.SyncResult

class SyncDatabaseUseCase(
    private val repository: SpamRepository,
) {
    suspend operator fun invoke(force: Boolean = false): SyncResult = repository.syncFromGitHub(force = force)
}
