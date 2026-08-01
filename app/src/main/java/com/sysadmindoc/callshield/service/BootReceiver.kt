package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/**
 * Re-asserts background protection after the two events that tear it down:
 *
 *  - `ACTION_BOOT_COMPLETED` — device reboot clears all scheduled work.
 *  - `ACTION_MY_PACKAGE_REPLACED` — an in-place app update (Play/Obtainium
 *    auto-update *without* a reboot) can leave the notification listener
 *    unbound and nothing re-scheduled, silently degrading protection until
 *    the next reboot.
 *
 * On either event it reschedules background work, re-enqueues the pending
 * blocked-call log flush, and asks the framework to rebind the RCS
 * notification listener. It also checks immediately whether Android revoked
 * the call-screening role.
 */
class BootReceiver : BroadcastReceiver() {
    internal var protectionReassertion: (Context) -> Unit = ::reassertProtection
    internal var lockedBootPreparation: (Context) -> Unit = { context ->
        DirectBootScreeningStore.read(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> lockedBootPreparation(context)

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> protectionReassertion(context)
        }
    }
}

private fun reassertProtection(context: Context) {
    (context.applicationContext as? com.sysadmindoc.callshield.CallShieldApp)?.initializeAfterUserUnlock()
    SyncWorker.schedule(context)
    HotListSyncWorker.schedule(context)
    DigestWorker.schedule(context)
    PendingBlockedCallLogWorker.schedule(context)
    ProtectionHealthWorker.schedule(context)
    ProtectionHealthWorker.checkNow(context)
    try {
        NotificationListenerService.requestRebind(
            ComponentName(context, RcsNotificationListener::class.java),
        )
    } catch (_: Exception) {
        // requestRebind throws if notification access was revoked; the
        // user must re-grant it — nothing to do from here.
    }
}
