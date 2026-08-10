package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.data.AppUpdateRelease
import com.sysadmindoc.callshield.data.AppUpdateState
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Checks the public release metadata only when the user opted into updates. */
@HiltWorker
class AppUpdateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: SpamRepository,
        private val github: GitHubDataSource,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!repository.appUpdateChecksEnabled.first()) return Result.success()

            val previous = repository.appUpdateState.first()
            val state =
                github
                    .fetchLatestRelease()
                    .fold(
                        onSuccess = { release -> AppUpdateState.fromRelease(BuildConfig.VERSION_NAME, release) },
                        onFailure = { AppUpdateState.unavailable() },
                    )
            repository.recordAppUpdateState(state)

            if (state.updateAvailable && state.latestTag != previous.latestTag) {
                state.toRelease()?.let { NotificationHelper.notifyAppUpdate(applicationContext, it) }
            }
            return Result.success()
        }

        private fun AppUpdateState.toRelease(): AppUpdateRelease? =
            if (releaseUrl.isNullOrBlank() || latestTag.isNullOrBlank()) {
                null
            } else {
                AppUpdateRelease(latestTag, releaseUrl, checksumUrl)
            }

        companion object {
            private const val PERIODIC_WORK_NAME = "callshield_app_update_check"
            private const val IMMEDIATE_WORK_NAME = "callshield_app_update_check_now"
            private const val CHECK_INTERVAL_DAYS = 7L

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            }

            fun checkNow(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    immediateRequest(),
                )
            }

            internal fun periodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<AppUpdateWorker>(CHECK_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setInitialDelay(CHECK_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()

            internal fun immediateRequest(): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<AppUpdateWorker>()
                    .setConstraints(networkConstraints())
                    .build()

            private fun networkConstraints(): Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
        }
    }
