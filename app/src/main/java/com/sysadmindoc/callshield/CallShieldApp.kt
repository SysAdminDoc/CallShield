package com.sysadmindoc.callshield

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.RestoreSentinel
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.SystemBlockList
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.remote.FeedMirror
import com.sysadmindoc.callshield.di.ApplicationScope
import com.sysadmindoc.callshield.service.AppUpdateWorker
import com.sysadmindoc.callshield.service.CrashReporter
import com.sysadmindoc.callshield.service.DigestWorker
import com.sysadmindoc.callshield.service.DirectBootScreeningStore
import com.sysadmindoc.callshield.service.ExternalBlocklistRefreshWorker
import com.sysadmindoc.callshield.service.HotDataSync
import com.sysadmindoc.callshield.service.HotListSyncWorker
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.PendingBlockedCallLogWorker
import com.sysadmindoc.callshield.service.ProtectionHealthWorker
import com.sysadmindoc.callshield.service.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class CallShieldApp :
    Application(),
    Configuration.Provider {
    @Volatile
    private var contactsObserverRegistered = false

    @Volatile
    private var unlockedInitializationComplete = false

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var checkerDependencies: CheckerDependencies

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() {
            return Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        appScope = applicationScope
        initializeAfterUserUnlock()
    }

    internal fun initializeAfterUserUnlock() {
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == false || unlockedInitializationComplete) return
        synchronized(this) {
            if (unlockedInitializationComplete) return
            val initStartedAt = SystemClock.elapsedRealtime()
            // Install the uncaught-exception handler BEFORE anything else so we
            // capture crashes even during app-startup init.
            CrashReporter.install(this)
            // Before any worker is scheduled, so one that runs at once waits for
            // the stored mirror setting instead of building its sources without it.
            FeedMirror.startLoading()
            val restoreCheckStartedAt = SystemClock.elapsedRealtime()
            val lookedForRestore = !RestoreSentinel.isClean(this)
            try {
                // A pending restore must reconcile before workers or screening
                // components can observe cross-store partial state. The sentinel
                // proves there's none without opening Room or initializing
                // BackupRestore (its reflective Moshi adapter alone took about
                // 500 ms), which together held the main thread 640 ms on every
                // start (API 29 emulator).
                if (lookedForRestore) {
                    runBlocking { BackupRestore.reconcilePendingRestore(this@CallShieldApp) }
                }
            } catch (e: Exception) {
                Log.e("CallShieldApp", "Failed to reconcile an interrupted restore", e)
            }
            val restoreCheckMillis = SystemClock.elapsedRealtime() - restoreCheckStartedAt
            // After the restore check, so the caches hold the restored rules.
            // Boot and app updates start the process here too, so the first
            // screened call no longer loads them inside its five-second budget.
            appScope.launch {
                try {
                    SpamRepository.getInstance(this@CallShieldApp).warmScreeningCaches()
                } catch (e: Exception) {
                    Log.w("CallShieldApp", "Failed to warm the screening caches", e)
                }
            }
            NotificationHelper.createChannels(this)
            SyncWorker.schedule(this)
            HotListSyncWorker.schedule(this)
            DigestWorker.schedule(this)
            ExternalBlocklistRefreshWorker.schedule(this)
            PendingBlockedCallLogWorker.schedule(this)
            ProtectionHealthWorker.schedule(this)
            ProtectionHealthWorker.checkNow(this)
            appScope.launch {
                val repository = SpamRepository.getInstance(this@CallShieldApp)
                if (repository.appUpdateChecksEnabled.first()) AppUpdateWorker.schedule(this@CallShieldApp)
            }

            registerCacheInvalidationObservers()

            appScope.launch {
                try {
                    SpamRepository.getInstance(this@CallShieldApp).feedMirrorUrl.collect { FeedMirror.set(it) }
                } catch (e: Exception) {
                    FeedMirror.loadFailed()
                    Log.w("CallShieldApp", "Failed to follow the feed mirror setting", e)
                }
            }

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

            appScope.launch {
                try {
                    SpamRepository.getInstance(this@CallShieldApp).purgeLegacyAbstractApiKey()
                } catch (e: Exception) {
                    Log.w("CallShieldApp", "Failed to purge legacy API key", e)
                }
            }
            appScope.launch {
                try {
                    DirectBootScreeningStore.observeAndMirror(
                        context = this@CallShieldApp,
                        repository = SpamRepository.getInstance(this@CallShieldApp),
                    )
                } catch (e: Exception) {
                    Log.w("CallShieldApp", "Failed to refresh direct-boot screening mirror", e)
                }
            }
            unlockedInitializationComplete = true
            startupTimings =
                StartupTimings(
                    initMillis = SystemClock.elapsedRealtime() - initStartedAt,
                    restoreCheckMillis = restoreCheckMillis,
                    readyAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    lookedForRestore = lookedForRestore,
                ).also { Log.i("CallShieldApp", "Startup held the main thread ${it.initMillis}ms (restore check ${it.restoreCheckMillis}ms)") }
        }
    }

    private fun registerCacheInvalidationObservers() {
        val handler = Handler(Looper.getMainLooper())

        ensureContactsObserver(handler)

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

    internal fun ensureContactsObserver(handler: Handler = Handler(Looper.getMainLooper())) {
        if (!contactsObserverRegistered &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) {
            synchronized(this) {
                if (!contactsObserverRegistered) {
                    try {
                        contentResolver.registerContentObserver(
                            ContactsContract.AUTHORITY_URI,
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
                        contactsObserverRegistered = true
                    } catch (_: SecurityException) {
                        // Permission can be revoked between the check and registration.
                    }
                }
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

        /** What the last unlocked initialization cost, read by the cold-start test. */
        @Volatile
        internal var startupTimings: StartupTimings? = null
            private set
    }
}

/**
 * Main-thread cost of [CallShieldApp.initializeAfterUserUnlock]. The restore
 * check is part of [initMillis]; [readyAtElapsedRealtime] is when it finished.
 * [lookedForRestore] is true on the one start after an install, an update or a
 * restore that had to open Room because [RestoreSentinel] had no proof yet.
 */
internal data class StartupTimings(
    val initMillis: Long,
    val restoreCheckMillis: Long,
    val readyAtElapsedRealtime: Long,
    val lookedForRestore: Boolean,
)
