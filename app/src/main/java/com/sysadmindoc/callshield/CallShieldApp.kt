package com.sysadmindoc.callshield

import android.app.Application
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.service.DigestWorker
import com.sysadmindoc.callshield.service.HotListSyncWorker
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        SyncWorker.schedule(this)
        HotListSyncWorker.schedule(this)
        DigestWorker.schedule(this)

        // Load ML model weights from local cache (fast, synchronous-style via file read)
        SpamMLScorer.loadWeights(this)

        // Auto-cleanup old log entries on launch
        CoroutineScope(Dispatchers.IO).launch {
            try { SpamRepository.getInstance(this@CallShieldApp).cleanupOldLogs() } catch (_: Exception) {}
        }
    }
}
