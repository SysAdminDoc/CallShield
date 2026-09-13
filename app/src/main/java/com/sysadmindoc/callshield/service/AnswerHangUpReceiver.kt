package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Forwards protected PHONE_STATE broadcasts to the pending answer-and-hang-up sequence. */
class AnswerHangUpReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!AnswerHangUpController.hasPendingCall()) return
        AnswerHangUpController.onPhoneState(
            state = intent.getStringExtra(TelephonyManager.EXTRA_STATE),
            incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),
        )
    }
}
