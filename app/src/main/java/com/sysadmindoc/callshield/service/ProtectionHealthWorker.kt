package com.sysadmindoc.callshield.service

import android.app.role.RoleManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

internal data class ProtectionHealthSnapshot(
    val onboardingDone: Boolean,
    val callBlockingEnabled: Boolean,
    val callScreeningRoleAvailable: Boolean,
    val callScreeningRoleHeld: Boolean,
    val callScreeningRoleEverHeld: Boolean = true,
    val lossNoticeShown: Boolean,
)

internal enum class ProtectionHealthAction {
    NONE,
    NOTIFY_ROLE_LOST,
    CLEAR_ROLE_LOSS_NOTICE,
}

internal fun evaluateProtectionHealth(snapshot: ProtectionHealthSnapshot): ProtectionHealthAction {
    val shouldProtectCalls =
        snapshot.onboardingDone &&
            snapshot.callBlockingEnabled &&
            snapshot.callScreeningRoleAvailable

    return when {
        !shouldProtectCalls && snapshot.lossNoticeShown -> ProtectionHealthAction.CLEAR_ROLE_LOSS_NOTICE

        !shouldProtectCalls -> ProtectionHealthAction.NONE

        snapshot.callScreeningRoleHeld && snapshot.lossNoticeShown -> ProtectionHealthAction.CLEAR_ROLE_LOSS_NOTICE

        snapshot.callScreeningRoleHeld -> ProtectionHealthAction.NONE

        snapshot.lossNoticeShown -> ProtectionHealthAction.NONE

        // Never notify an install that never held the role ("Continue anyway"
        // onboarding path) — the alert's copy claims Android *stopped* routing
        // calls through CallShield, which would be false and reads as a nag.
        !snapshot.callScreeningRoleEverHeld -> ProtectionHealthAction.NONE

        else -> ProtectionHealthAction.NOTIFY_ROLE_LOST
    }
}

/** Periodically verifies that Android is still routing incoming calls through CallShield. */
@HiltWorker
class ProtectionHealthWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: SpamRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val roleManager = applicationContext.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            val noticeShown = repository.protectionRoleLossNoticeShown.first()
            val roleHeld = CallShieldPermissions.hasCallScreeningRole(roleManager)
            val everHeldStored = repository.protectionRoleEverHeld.first()
            val everHeld = everHeldStored || roleHeld
            if (roleHeld && !everHeldStored) repository.setProtectionRoleEverHeld()
            val action =
                evaluateProtectionHealth(
                    ProtectionHealthSnapshot(
                        onboardingDone = repository.onboardingDone.first(),
                        callBlockingEnabled = repository.blockCallsEnabled.first(),
                        callScreeningRoleAvailable = CallShieldPermissions.isCallScreeningRoleAvailable(roleManager),
                        callScreeningRoleHeld = roleHeld,
                        callScreeningRoleEverHeld = everHeld,
                        lossNoticeShown = noticeShown,
                    ),
                )

            when (action) {
                ProtectionHealthAction.NONE -> {
                    Unit
                }

                ProtectionHealthAction.CLEAR_ROLE_LOSS_NOTICE -> {
                    repository.setProtectionRoleLossNoticeShown(false)
                    NotificationHelper.dismissCallScreeningRoleLost(applicationContext)
                }

                ProtectionHealthAction.NOTIFY_ROLE_LOST -> {
                    if (NotificationHelper.notifyCallScreeningRoleLost(applicationContext)) {
                        repository.setProtectionRoleLossNoticeShown(true)
                    }
                }
            }
            return Result.success()
        }

        companion object {
            private const val PERIODIC_WORK_NAME = BackgroundWorkNames.PROTECTION_HEALTH
            private const val IMMEDIATE_WORK_NAME = "callshield_protection_health_now"
            private const val CHECK_INTERVAL_HOURS = 24L

            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }

            fun checkNow(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    immediateRequest(),
                )
            }

            internal fun periodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<ProtectionHealthWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setInitialDelay(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                    .build()

            internal fun immediateRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ProtectionHealthWorker>().build()
        }
    }
