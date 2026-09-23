package com.sysadmindoc.callshield.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.sysadmindoc.callshield.data.OutgoingCallGuard
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.util.startActivitySafely

/**
 * The "Call anyway" action on a held outgoing call. It lets the number
 * through for a couple of minutes and opens the dialer with it, so the user
 * places the call themselves. It never shows a screen of its own, and it
 * needs no call permission.
 */
class CallAnywayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER)
        if (!number.isNullOrBlank()) {
            // Keyed for every spelling, since the dialer may redial it with or without +1.
            val key = OutgoingCallGuard.bypassKey(number, PhoneIdentityCanonicalizer.cachedFromContext(this).homeRegionIso)
            OutgoingCallGuard.allow(key, SystemClock.elapsedRealtime())
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId >= 0) {
                getSystemService(NotificationManager::class.java)?.cancel(notificationId)
            }
            startActivitySafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        }
        finish()
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
