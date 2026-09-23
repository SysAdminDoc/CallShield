package com.sysadmindoc.callshield.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Which meeting and calling apps hold an ongoing notification right now.
 *
 * A video meeting or VoIP call keeps an ongoing (foreground-service or
 * call-style) notification up for exactly as long as it lasts, and the
 * notification listener already sees it. That makes it a meeting signal that
 * needs no calendar access and no usage-stats permission. Ordinary message
 * notifications from the same apps are not ongoing, so an unread Slack message
 * doesn't count.
 *
 * Fed by [com.sysadmindoc.callshield.service.RcsNotificationListener]; read by
 * [com.sysadmindoc.callshield.data.checker.MeetingModeChecker] on the screening
 * path. Every catalog app is tracked whether or not the user picked it, so a
 * change of selection needs no re-sync. The state is in memory only and is
 * cleared when the listener disconnects: with no removal callbacks arriving, a
 * stale entry would keep silencing calls after the meeting ended.
 */
object MeetingModeRegistry {
    /** Apps offered in the meeting-mode picker, with a display name for when the app isn't installed. */
    val MEETING_APPS: Map<String, String> =
        linkedMapOf(
            "us.zoom.videomeetings" to "Zoom",
            "com.microsoft.teams" to "Microsoft Teams",
            "com.google.android.apps.tachyon" to "Google Meet",
            "com.cisco.wx2.android" to "Webex",
            "com.Slack" to "Slack",
            "com.logmein.gotoconnect" to "GoTo",
            "org.jitsi.meet" to "Jitsi Meet",
            "com.discord" to "Discord",
            "com.whatsapp" to "WhatsApp",
            "org.thoughtcrime.securesms" to "Signal",
            "org.telegram.messenger" to "Telegram",
            "com.facebook.orca" to "Messenger",
        )

    /** Notification key to package, for the ongoing notifications of catalog apps. */
    private val ongoing = ConcurrentHashMap<String, String>()

    fun displayName(packageName: String): String = MEETING_APPS[packageName] ?: packageName

    /** A notification was posted or updated. An update that drops the ongoing flag ends that meeting. */
    fun onPosted(
        key: String,
        packageName: String,
        isOngoing: Boolean,
    ) {
        if (packageName !in MEETING_APPS) return
        if (isOngoing) ongoing[key] = packageName else ongoing.remove(key)
    }

    fun onRemoved(key: String) {
        ongoing.remove(key)
    }

    /** Rebuilds the state from the listener's active notifications after it (re)connects. */
    fun replaceAll(active: List<ActiveNotification>) {
        ongoing.clear()
        active.forEach { onPosted(it.key, it.packageName, it.isOngoing) }
    }

    fun clear() {
        ongoing.clear()
    }

    /** Catalog apps with an ongoing notification now. */
    fun activePackages(): Set<String> = ongoing.values.toSet()

    data class ActiveNotification(
        val key: String,
        val packageName: String,
        val isOngoing: Boolean,
    )
}
