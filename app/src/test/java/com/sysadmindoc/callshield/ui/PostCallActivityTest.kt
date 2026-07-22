package com.sysadmindoc.callshield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PostCallActivityTest {
    @Test
    fun `parses platform post-call extras`() {
        val details =
            PostCallIntentParser.parse(
                Intent(TelecomManager.ACTION_POST_CALL)
                    .putExtra(TelecomManager.EXTRA_HANDLE, Uri.parse("tel:+12125550123"))
                    .putExtra(TelecomManager.EXTRA_CALL_DURATION, TelecomManager.DURATION_MEDIUM)
                    .putExtra(TelecomManager.EXTRA_DISCONNECT_CAUSE, DisconnectCause.REMOTE),
            )

        requireNotNull(details)
        assertEquals("+12125550123", details.number)
        assertEquals(TelecomManager.DURATION_MEDIUM, details.durationBucket)
        assertEquals(DisconnectCause.REMOTE, details.disconnectCause)
    }

    @Test
    fun `normalizes untrusted phone handle with ASCII-only contract`() {
        val details =
            PostCallIntentParser.parse(
                Intent(TelecomManager.ACTION_POST_CALL)
                    .putExtra(TelecomManager.EXTRA_HANDLE, Uri.parse("tel:%E2%80%8E+1%20(212)%20555-0123")),
            )

        assertEquals("+12125550123", requireNotNull(details).number)
    }

    @Test
    fun `rejects unrelated action`() {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .putExtra(TelecomManager.EXTRA_HANDLE, Uri.parse("tel:+12125550123"))

        assertNull(PostCallIntentParser.parse(intent))
    }

    @Test
    fun `rejects non-telephone handles`() {
        val intent =
            Intent(TelecomManager.ACTION_POST_CALL)
                .putExtra(TelecomManager.EXTRA_HANDLE, Uri.parse("sip:caller@example.com"))

        assertNull(PostCallIntentParser.parse(intent))
    }

    @Test
    fun `manifest exposes only the platform post-call handler`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handlers =
            context.packageManager.queryIntentActivities(
                Intent(TelecomManager.ACTION_POST_CALL).setPackage(context.packageName),
                0,
            )

        val activity = handlers.single { it.activityInfo.name == PostCallActivity::class.java.name }
        assertTrue(activity.activityInfo.exported)
    }
}
