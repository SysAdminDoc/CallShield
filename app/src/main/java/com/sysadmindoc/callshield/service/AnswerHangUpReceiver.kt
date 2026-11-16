package com.sysadmindoc.callshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Forwards protected PHONE_STATE broadcasts to the pending answer-and-hang-up sequence, and
 * to a microphone mute still to undo, which can outlive the process that set it.
 */
class AnswerHangUpReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        AnswerHangUpController.configure(context)
        if (!AnswerHangUpController.wantsPhoneState()) return
        AnswerHangUpController.onPhoneState(
            state = intent.getStringExtra(TelephonyManager.EXTRA_STATE),
            incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),
            hasIncomingNumber = intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),
        )
    }
}
