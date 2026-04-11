package com.sysadmindoc.callshield

import android.app.Application
import android.util.Log
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.service.DigestWorker
import com.sysadmindoc.callshield.service.HotListSyncWorker
import com.sysadmindoc.callshield.service.HotDataSync
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallShieldApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        SyncWorker.schedule(this)
        HotListSyncWorker.schedule(this)
        DigestWorker.schedule(this)

        applicationScope.launch {
            SpamMLScorer.loadWeights(this@CallShieldApp)
        }

        applicationScope.launch {
            try {
                HotDataSync.primeBundled(this@CallShieldApp)
            } catch (e: Exception) {
                Log.w("CallShieldApp", "Failed to prime bundled hot data", e)
            }
        }

        applicationScope.launch {
            try {
                SpamRepository.getInstance(this@CallShieldApp).cleanupOldLogs()
            } catch (e: Exception) {
                Log.w("CallShieldApp", "Failed to clean up old logs", e)
            }
        }
    }
}
