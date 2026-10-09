package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.MeetingModeRegistry
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Texts that only reach CallShield as a messaging app's notification, as RCS chats do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsNotificationListenerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val fixture = IsolatedRepositoryFixture(context)
    private lateinit var listener: RcsNotificationListener
    private val caller = "+12025550143"

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        UnknownCallWindow.shared.clear()
        SpamRepository.replaceInstanceForTests(fixture.repository)
        listener = Robolectric.setupService(RcsNotificationListener::class.java)
    }

    @After
    fun tearDown() {
        listener.onDestroy()
        SpamRepository.replaceInstanceForTests(null)
        UnknownCallWindow.shared.clear()
        PushAlertRegistry.clear()
        notificationManager.cancelAll()
        fixture.close()
    }

    @Test
    fun `a code in a chat notification during an unknown call warns`() {
        UnknownCallWindow.shared.callStarted(caller)

        postUntil(chatNotification("Your Chase verification code is 482913. Don't share it.")) { codeWarnings().isNotEmpty() }

        assertEquals(
            context.getString(R.string.notif_code_during_call_text, PhoneFormatter.formatIsolated(caller)),
            codeWarnings().single().extras.getString(Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun `a chat flagged by its words alone keeps the number it asks you to call`() {
        val scam = "Congratulations, you have won. Claim your prize now at bit.ly/example or call +1 800 345 1234"

        postUntil(chatNotification(scam, sender = "Prize Center")) {
            fixture.repository.lastFlaggedTextSighting("+18003451234", System.currentTimeMillis()) != null
        }
    }

    @Test
    fun `a meeting app's background Connected notification doesn't start meeting mode, its call does`() {
        MeetingModeRegistry.clear()

        listener.onNotificationPosted(teamsNotification(id = 1) { setContentTitle("Microsoft Teams").setContentText("Connected") })
        assertEquals(emptySet<String>(), MeetingModeRegistry.activePackages())

        val colleague = Person.Builder().setName("Dana Whitfield").build()
        val hangUp = PendingIntent.getBroadcast(context, 0, Intent("hang_up"), PendingIntent.FLAG_IMMUTABLE)
        listener.onNotificationPosted(teamsNotification(id = 2) { setStyle(Notification.CallStyle.forOngoingCall(colleague, hangUp)) })
        assertEquals(setOf(TEAMS), MeetingModeRegistry.activePackages())
    }

    /** An ongoing notification from Teams, the way a foreground service posts one. */
    @Suppress("DEPRECATION")
    private fun teamsNotification(
        id: Int,
        configure: Notification.Builder.() -> Unit,
    ) = StatusBarNotification(
        TEAMS,
        TEAMS,
        id,
        null,
        Process.myUid(),
        0,
        0,
        Notification
            .Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .apply(configure)
            .build(),
        Process.myUserHandle(),
        System.currentTimeMillis(),
    )

    /** The listener reads nothing until the user's chosen apps have loaded, so it's posted until [done]. */
    private fun postUntil(
        chat: StatusBarNotification,
        done: suspend () -> Boolean,
    ) = runBlocking {
        withTimeout(5_000L) {
            while (!done()) {
                listener.onNotificationPosted(chat)
                delay(50L)
            }
        }
    }

    private fun codeWarnings() = shadowOf(notificationManager).allNotifications.filter { it.channelId == NotificationHelper.CHANNEL_CODE_DURING_CALL }

    @Suppress("DEPRECATION")
    private fun chatNotification(
        body: String,
        sender: String? = null,
    ) = StatusBarNotification(
        GOOGLE_MESSAGES,
        GOOGLE_MESSAGES,
        1,
        null,
        Process.myUid(),
        0,
        0,
        Notification
            .Builder(context, "chats")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(sender)
            .setContentText(body)
            .build(),
        Process.myUserHandle(),
        System.currentTimeMillis(),
    )

    private companion object {
        const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
        const val TEAMS = "com.microsoft.teams"
    }
}
