package com.sysadmindoc.callshield.domain.usecase

import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.domain.repository.SyncRepository

class SyncDatabaseUseCase(
    private val repository: SyncRepository,
) {
    suspend operator fun invoke(force: Boolean = false): SyncResult = repository.syncDatabase(force = force)
}
