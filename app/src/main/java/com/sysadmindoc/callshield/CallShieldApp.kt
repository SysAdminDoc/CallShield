package com.sysadmindoc.callshield

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.SystemBlockList
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.service.CrashReporter
import com.sysadmindoc.callshield.service.DigestWorker
import com.sysadmindoc.callshield.service.HotDataSync
import com.sysadmindoc.callshield.service.HotListSyncWorker
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.PendingBlockedCallLogWorker
import com.sysadmindoc.callshield.service.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CallShieldApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var checkerDependencies: CheckerDependencies

    override val workManagerConfiguration: Configuration
        get() {
            return Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        // Install the uncaught-exception handler BEFORE anything else so we
        // capture crashes even during app-startup init.
        CrashReporter.install(this)
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        NotificationHelper.createChannels(this)
        SyncWorker.schedule(this)
        HotListSyncWorker.schedule(this)
        DigestWorker.schedule(this)
        PendingBlockedCallLogWorker.schedule(this)

        registerCacheInvalidationObservers()

        appScope.launch {
            checkerDependencies.spamMLScorer.loadWeights(this@CallShieldApp)
        }

        appScope.launch {
            try {
                HotDataSync.primeBundled(
                    context = this@CallShieldApp,
                    dependencies = checkerDependencies,
                )
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

    private fun registerCacheInvalidationObservers() {
        val handler = Handler(Looper.getMainLooper())

        registerContactsObserver(handler)

        try {
            contentResolver.registerContentObserver(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                true,
                object : ContentObserver(handler) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        SystemBlockList.clearCache()
                    }
                },
            )
        } catch (_: SecurityException) {
            // Not the default dialer — BlockedNumberContract may not be readable.
        }
    }

    private fun registerContactsObserver(handler: Handler) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return

        try {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                object : ContentObserver(handler) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        checkerDependencies.spamHeuristics.clearContactCache()
                    }
                },
            )
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and registration.
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
