package com.sysadmindoc.callshield.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import java.io.IOException

/** Applies the changes [ProtectionHold] kept waiting once the call no longer counts, and looks again while it does. */
class ProtectionHoldWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            // Appended, so the next look starts once this run has finished.
            ProtectionHold.releaseDue(applicationContext)?.let {
                ProtectionHold.schedule(applicationContext, it, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            Result.success()
        } catch (e: IOException) {
            Log.w("ProtectionHoldWorker", "Couldn't apply the held changes", e)
            Result.retry()
        }
}
