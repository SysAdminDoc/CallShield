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
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.data.remote.UrlThreatCategory
import com.sysadmindoc.callshield.data.remote.UrlThreatSource
import com.sysadmindoc.callshield.ui.urlThreatLabels
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

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
    fun `a link check that throws doesn't take the app down`() {
        val crashes = CopyOnWriteArrayList<Throwable>()
        val checked = CountDownLatch(1)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val realCheck = RcsNotificationListener.urlCheck
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        RcsNotificationListener.urlCheck = { _, _, _, _ ->
            checked.countDown()
            error("threat feed unreadable")
        }
        try {
            postUntil(chatNotification("Here are the notes from today https://example.com/notes", sender = "Dana")) { checked.count == 0L }
            // An escaped throw reaches the handler right after the check, on the listener's thread.
            Thread.sleep(500L)
            assertEquals(emptyList<Throwable>(), crashes.toList())
        } finally {
            RcsNotificationListener.urlCheck = realCheck
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `a flagged link in an opted-in WhatsApp message gets the phishing alert from on-device checks alone`() {
        runBlocking { fixture.repository.setNotificationScreeningPackages(setOf(GOOGLE_MESSAGES, WHATSAPP)) }
        val onDeviceOnlyAsked = withFlaggingLinkCheck()
        try {
            postUntil(chatNotification(NOTES, sender = "Dana", packageName = WHATSAPP)) { phishingAlerts().isNotEmpty() }

            assertEquals(
                context.getString(R.string.notif_phishing_app_text, "WhatsApp", "Dana", urlThreatLabels(context, listOf(PHISHING_LINK))),
                phishingAlerts().single().extras.getString(Notification.EXTRA_TEXT),
            )
            assertEquals(listOf(true), onDeviceOnlyAsked.distinct())
            assertEquals(0, callLogRows())
        } finally {
            RcsNotificationListener.urlCheck = realUrlCheck
        }
    }

    @Test
    fun `an RCS text whose screening hits an error keeps a diagnostic row`() {
        withKeywordCheckFailing()

        postUntil(chatNotification(NOTES, sender = caller)) { callLogTypes().isNotEmpty() }

        assertEquals(setOf("screening_diagnostic"), callLogTypes().toSet())
    }

    @Test
    fun `a WhatsApp message whose screening hits an error writes no diagnostic row`() {
        runBlocking { fixture.repository.setNotificationScreeningPackages(setOf(GOOGLE_MESSAGES, WHATSAPP)) }
        // The RCS test above shows this failure writes a row for a text.
        withKeywordCheckFailing()
        withFlaggingLinkCheck()
        try {
            // The link check runs only once no row was written, so either one ends the wait.
            postUntil(chatNotification(NOTES, sender = caller, packageName = WHATSAPP)) {
                phishingAlerts().isNotEmpty() || callLogTypes().isNotEmpty()
            }

            assertEquals(emptyList<String>(), callLogTypes())
        } finally {
            RcsNotificationListener.urlCheck = realUrlCheck
        }
    }

    @Test
    fun `an RCS link check still uses the lookups the user turned on`() {
        val onDeviceOnlyAsked = withFlaggingLinkCheck()
        try {
            postUntil(chatNotification(NOTES, sender = "Dana")) { phishingAlerts().isNotEmpty() }

            assertEquals(
                context.getString(R.string.notif_phishing_app_text, "Google Messages", "Dana", urlThreatLabels(context, listOf(PHISHING_LINK))),
                phishingAlerts().single().extras.getString(Notification.EXTRA_TEXT),
            )
            assertEquals(listOf(false), onDeviceOnlyAsked.distinct())
        } finally {
            RcsNotificationListener.urlCheck = realUrlCheck
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

    private fun phishingAlerts() = shadowOf(notificationManager).allNotifications.filter { it.channelId == NotificationHelper.CHANNEL_PHISHING }

    private fun callLogRows() =
        fixture.sqlite.query("SELECT COUNT(*) FROM call_log").use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** Makes [caller]'s screening come back clean but with the keyword check failed, so it carries a diagnostic. */
    private fun withKeywordCheckFailing() {
        // The scorer flags the number on its own, which would end screening before the keyword check.
        runBlocking { fixture.repository.setMlScorer(false) }
        fixture.damageTable("sms_keyword_rules")
    }

    private fun callLogTypes() =
        fixture.sqlite.query("SELECT type FROM call_log").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }

    private val realUrlCheck = RcsNotificationListener.urlCheck

    /** Flags every link, so the alert doesn't depend on a feed, and returns what each check was asked. */
    private fun withFlaggingLinkCheck(): List<Boolean> {
        val onDeviceOnlyAsked = CopyOnWriteArrayList<Boolean>()
        RcsNotificationListener.urlCheck = { _, _, _, onDeviceOnly ->
            onDeviceOnlyAsked += onDeviceOnly
            listOf(PHISHING_LINK)
        }
        return onDeviceOnlyAsked
    }

    @Suppress("DEPRECATION")
    private fun chatNotification(
        body: String,
        sender: String? = null,
        packageName: String = GOOGLE_MESSAGES,
    ) = StatusBarNotification(
        packageName,
        packageName,
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
        const val WHATSAPP = "com.whatsapp"
        const val TEAMS = "com.microsoft.teams"
        const val NOTES = "Here are the notes from today https://example.com/notes"
        val PHISHING_LINK =
            UrlSafetyChecker.UrlCheckResult(
                url = "https://example.com",
                isMalicious = true,
                source = UrlThreatSource.OPENPHISH,
                category = UrlThreatCategory.PHISHING,
            )
    }
}
