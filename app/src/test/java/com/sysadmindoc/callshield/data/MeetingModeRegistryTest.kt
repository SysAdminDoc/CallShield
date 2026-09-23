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
        MeetingModeRegistry.onPosted("call-1", "com.whatsapp", isOngoing = true)
        MeetingModeRegistry.onPosted("call-2", "com.whatsapp", isOngoing = true)

        MeetingModeRegistry.onRemoved("call-1")

        assertEquals(setOf("com.whatsapp"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `a reconnect replaces the state with what is on screen`() {
        MeetingModeRegistry.onPosted("stale", "com.discord", isOngoing = true)

        MeetingModeRegistry.replaceAll(
            listOf(
                MeetingModeRegistry.ActiveNotification("meet", "com.google.android.apps.tachyon", isOngoing = true),
                MeetingModeRegistry.ActiveNotification("chat", "com.Slack", isOngoing = false),
            ),
        )

        assertEquals(setOf("com.google.android.apps.tachyon"), MeetingModeRegistry.activePackages())
    }

    @Test
    fun `clear forgets every meeting`() {
        MeetingModeRegistry.onPosted("meeting", "org.jitsi.meet", isOngoing = true)

        MeetingModeRegistry.clear()

        assertTrue(MeetingModeRegistry.activePackages().isEmpty())
    }
}
