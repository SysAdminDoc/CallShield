package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodeDuringCallWarningTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var now = 1_000_000L
    private val window = UnknownCallWindow { now }
    private val caller = "+12025550143"
    private val code = "Your Chase verification code is 482913. Don't share it."

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun `a code during an unknown call posts one high-priority warning`() {
        window.callStarted(caller)
        now += 2 * MINUTE

        assertTrue(warn(code))
        assertFalse("once per call", warn("Your new code is 771204"))

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(NotificationHelper.CHANNEL_CODE_DURING_CALL, notification.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_CODE_DURING_CALL).importance,
        )
        assertEquals(context.getString(R.string.notif_code_during_call_title), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            context.getString(R.string.notif_code_during_call_text, PhoneFormatter.formatIsolated(caller)),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun `never for a contact`() {
        window.callStarted(caller)

        assertFalse(warn(code, isContact = { true }))
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `never for an ordinary text or with no unknown call`() {
        assertFalse("no call yet", warn(code))
        window.callStarted(caller)
        assertFalse(warn("Running late, be there at 5"))
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a hidden caller is named as a hidden number`() {
        window.callStarted("")

        assertTrue(warn(code, isContact = { error("a hidden number is never looked up") }))

        val text =
            shadowOf(notificationManager)
                .allNotifications
                .single()
                .extras
                .getString(Notification.EXTRA_TEXT)
        assertEquals(
            context.getString(R.string.notif_code_during_call_text, context.getString(R.string.notif_code_during_call_hidden_caller)),
            text,
        )
    }

    @Test
    fun `an allowed unknown call opens the window and a trusted one doesn't`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)

        CodeDuringCallWarning.onCallAllowed(scope, context, caller, "manual_whitelist", window)
        assertNull(window.openCaller())

        CodeDuringCallWarning.onCallAllowed(scope, context, caller, "", window)
        // No phone-state permission here, so the call counts as ended when it rang.
        now += UnknownCallWindow.AFTER_CALL_MS
        assertEquals(caller, window.openCaller())
        now += 1
        assertNull(window.openCaller())
    }

    @Test
    fun `a call nothing screened is watched unless its number is on the allow list`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val unscreened = CodeDuringCallWarning.UNSCREENED

        CodeDuringCallWarning.onCallAllowed(scope, context, caller, unscreened, window, isTrusted = { true })
        assertNull(window.openCaller())

        CodeDuringCallWarning.onCallAllowed(scope, context, "", unscreened, window, isTrusted = { error("a hidden number is never looked up") })
        assertEquals("", window.openCaller())

        CodeDuringCallWarning.onCallAllowed(scope, context, caller, unscreened, window, isTrusted = { false })
        assertEquals(caller, window.openCaller())
    }

    @Test
    fun `an allow list that can't be read doesn't stop the watch`() =
        runBlocking {
            assertFalse(CodeDuringCallWarning.allowListed({ error("the database is locked") }, caller))
        }

    @Test
    fun `a text that starts a new process still finds the call`() {
        CodeDuringCallWarning.onCallAllowed(CoroutineScope(Dispatchers.Unconfined), context, caller, "", window)
        now += 3 * MINUTE

        assertTrue(CodeDuringCallWarning.onMessage(context, code, UnknownCallWindow { now }, isContact = { false }))
        assertFalse(
            "the warning was saved with the call",
            CodeDuringCallWarning.onMessage(context, code, UnknownCallWindow { now }, isContact = { false }),
        )
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun `a saved call that has closed is forgotten`() {
        CodeDuringCallWarning.onCallAllowed(CoroutineScope(Dispatchers.Unconfined), context, caller, "", window)
        now += UnknownCallWindow.AFTER_CALL_MS + 1

        assertFalse(CodeDuringCallWarning.onMessage(context, code, UnknownCallWindow { now }, isContact = { false }))
        assertTrue(context.getSharedPreferences("code_during_call", Context.MODE_PRIVATE).all.isEmpty())
    }

    private fun warn(
        body: String,
        isContact: (String) -> Boolean = { false },
    ): Boolean = CodeDuringCallWarning.onMessage(context, body, window, isContact)

    private companion object {
        const val MINUTE = 60_000L
    }
}
