package com.sysadmindoc.callshield

import android.app.Application
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.service.SyncWorker

class CallShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        SyncWorker.schedule(this)
    }
}
