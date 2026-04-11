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

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        NotificationHelper.createChannels(this)
        SyncWorker.schedule(this)
        HotListSyncWorker.schedule(this)
        DigestWorker.schedule(this)

        appScope.launch {
            SpamMLScorer.loadWeights(this@CallShieldApp)
        }

        appScope.launch {
            try {
                HotDataSync.primeBundled(this@CallShieldApp)
            } catch (e: Exception) {
                Log.w("CallShieldApp", "Failed to prime bundled hot data", e)
            }
        }

        appScope.launch {
            try {
                SpamRepository.getInstance(this@CallShieldApp).cleanupOldLogs()
            } catch (e: Exception) {
                Log.w("CallShieldApp", "Failed to clean up old logs", e)
            }
        }
    }

    companion object {
        /**
         * Process-lifetime coroutine scope for fire-and-forget work that MUST
         * outlive the component that launched it — specifically the 10-second
         * after-call feedback notification scheduled by
         * CallShieldScreeningService, which is typically unbound by the system
         * seconds after respondToCall() returns.
         *
         * Do NOT use this for work that should be cancelled when a UI surface
         * goes away (use viewModelScope / rememberCoroutineScope for that).
         * Do NOT add long-running loops here — the scope never cancels.
         *
         * Populated in onCreate(); reads before onCreate() will crash, which
         * is intentional (they indicate an ordering bug).
         */
        lateinit var appScope: CoroutineScope
            private set
    }
}
