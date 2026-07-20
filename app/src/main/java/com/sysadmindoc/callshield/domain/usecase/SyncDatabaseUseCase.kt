package com.sysadmindoc.callshield.domain.usecase

import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.domain.repository.SyncRepository
import javax.inject.Inject

class SyncDatabaseUseCase
    @Inject
    constructor(
        private val repository: SyncRepository,
    ) {
        suspend operator fun invoke(force: Boolean = false): SyncResult = repository.syncDatabase(force = force)
    }
