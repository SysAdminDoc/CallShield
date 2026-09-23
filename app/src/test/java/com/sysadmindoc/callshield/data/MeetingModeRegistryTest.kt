package com.sysadmindoc.callshield.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingModeRegistryTest {
    @After
    fun tearDown() {
        MeetingModeRegistry.clear()
    }

    @Test
    fun `an ongoing notification from a catalog app marks it in a meeting`() {
        MeetingModeRegistry.onPosted("0|us.zoom.videomeetings|1|null|10", "us.zoom.videomeetings", isOngoing = true)

        assertEquals(setOf("us.zoom.videomeetings"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a message notification from the same app is not a meeting`() {
        MeetingModeRegistry.onPosted("0|com.Slack|7|null|10", "com.Slack", isOngoing = false)

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }

    @Test
    fun `apps outside the catalog are ignored`() {
        MeetingModeRegistry.onPosted("0|com.spotify.music|1|null|10", "com.spotify.music", isOngoing = true)

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }

    @Test
    fun `the meeting ends when its notification is removed or stops being ongoing`() {
        MeetingModeRegistry.onPosted("teams-call", "com.microsoft.teams", isOngoing = true)
        MeetingModeRegistry.onPosted("zoom-meeting", "us.zoom.videomeetings", isOngoing = true)

        MeetingModeRegistry.onRemoved("teams-call")
        MeetingModeRegistry.onPosted("zoom-meeting", "us.zoom.videomeetings", isOngoing = false)

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }

    @Test
    fun `an app stays in a meeting while any of its ongoing notifications remains`() {
        MeetingModeRegistry.onPosted("call-1", "com.whatsapp", isOngoing = true, category = "call")
        MeetingModeRegistry.onPosted("call-2", "com.whatsapp", isOngoing = true, category = "call")

        MeetingModeRegistry.onRemoved("call-1")

        assertEquals(setOf("com.whatsapp"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a messenger's ongoing notification counts only when it is a call`() {
        // Signal's background connection, a WhatsApp backup and Telegram's keep-alive stay up for hours.
        MeetingModeRegistry.onPosted("signal-service", "org.thoughtcrime.securesms", isOngoing = true)
        MeetingModeRegistry.onPosted("whatsapp-backup", "com.whatsapp", isOngoing = true, category = "progress")
        MeetingModeRegistry.onPosted("telegram-service", "org.telegram.messenger", isOngoing = true, category = "service")
        MeetingModeRegistry.onPosted("messenger-heads", "com.facebook.orca", isOngoing = true)
        assertTrue(MeetingModeRegistry.activePackages().isEmpty())

        MeetingModeRegistry.onPosted("telegram-call", "org.telegram.messenger", isOngoing = true, category = "call")
        assertEquals(setOf("org.telegram.messenger"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a Telegram call counts by the notification id its call service uses`() {
        // Telegram's in-call notification (VoIPService id 201) is ongoing but has no category.
        MeetingModeRegistry.onPosted("telegram-keepalive", "org.telegram.messenger", isOngoing = true, notificationId = 5)
        assertTrue(MeetingModeRegistry.activePackages().isEmpty())

        MeetingModeRegistry.onPosted("telegram-call", "org.telegram.messenger", isOngoing = true, notificationId = 201)
        assertEquals(setOf("org.telegram.messenger"), MeetingModeRegistry.activePackages())

        // The id only identifies Telegram's calls, not another messenger's.
        MeetingModeRegistry.onPosted("whatsapp-201", "com.whatsapp", isOngoing = true, notificationId = 201)
        assertEquals(setOf("org.telegram.messenger"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a messenger call that turns into another ongoing notification ends the meeting`() {
        MeetingModeRegistry.onPosted("whatsapp-1", "com.whatsapp", isOngoing = true, category = "call")

        MeetingModeRegistry.onPosted("whatsapp-1", "com.whatsapp", isOngoing = true, category = null)

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }

    @Test
    fun `a dedicated meeting app counts on any ongoing notification`() {
        // Zoom and Teams keep an ongoing notification only while a meeting or call runs.
        MeetingModeRegistry.onPosted("zoom", "us.zoom.videomeetings", isOngoing = true, category = null)

        assertEquals(setOf("us.zoom.videomeetings"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a reconnect replaces the state with what is on screen`() {
        MeetingModeRegistry.onPosted("stale", "com.discord", isOngoing = true)

        MeetingModeRegistry.replaceAll(
            listOf(
                MeetingModeRegistry.ActiveNotification("meet", "com.google.android.apps.tachyon", isOngoing = true),
                MeetingModeRegistry.ActiveNotification("chat", "com.Slack", isOngoing = false),
                MeetingModeRegistry.ActiveNotification("signal-service", "org.thoughtcrime.securesms", isOngoing = true),
                MeetingModeRegistry.ActiveNotification("whatsapp-call", "com.whatsapp", isOngoing = true, category = "call"),
                MeetingModeRegistry.ActiveNotification("telegram-call", "org.telegram.messenger", isOngoing = true, notificationId = 201),
            ),
        )

        assertEquals(
            setOf("com.google.android.apps.tachyon", "com.whatsapp", "org.telegram.messenger"),
            MeetingModeRegistry.activePackages(),
        )
    }

    @Test
    fun `clear forgets every meeting`() {
        MeetingModeRegistry.onPosted("meeting", "org.jitsi.meet", isOngoing = true)

        MeetingModeRegistry.clear()

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }
}
