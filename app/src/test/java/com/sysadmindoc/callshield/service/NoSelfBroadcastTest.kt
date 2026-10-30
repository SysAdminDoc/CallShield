package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android 17 QPR2 stops a broadcast an app sends to itself from raising the
 * sender's priority and drops it when the process dies first, so the app
 * never hands its own work to a receiver by broadcast: the post-call screen
 * calls [SpamActionReceiver.spamFeedback] and the widget renders itself.
 * The notification actions still reach [SpamActionReceiver], but those
 * broadcasts come from the system through a PendingIntent, not from this
 * process. A broadcast meant for another app would need its own entry here.
 */
class NoSelfBroadcastTest {
    @Test
    fun `no source sends a broadcast`() {
        val senders =
            File("src/main/java")
                .walk()
                .filter { it.extension == "kt" }
                .filter { Regex("""\bsend(Ordered)?Broadcast(AsUser)?\(""").containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()

        assertEquals(emptyList<String>(), senders)
    }
}
