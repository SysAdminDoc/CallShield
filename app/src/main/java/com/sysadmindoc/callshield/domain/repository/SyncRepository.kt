package com.sysadmindoc.callshield.domain.repository

import com.sysadmindoc.callshield.domain.model.SyncResult

interface SyncRepository {
    suspend fun syncDatabase(force: Boolean): SyncResult
}
