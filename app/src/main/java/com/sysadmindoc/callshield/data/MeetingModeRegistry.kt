package com.sysadmindoc.callshield.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Which meeting and calling apps show a call in progress right now.
 *
 * A video meeting or VoIP call keeps an ongoing call notification up for
 * exactly as long as it lasts, and the notification listener already sees it.
 * That makes it a meeting signal that needs no calendar access and no
 * usage-stats permission. Being ongoing isn't enough: these apps also keep
 * ongoing notifications up for a background connection, a sync or an upload,
 * sometimes for hours. So a notification counts only when it is ongoing and
 * marked as a call, by the call category or the call style template. Ordinary
 * message notifications are neither, so an unread Slack message doesn't count.
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

    /**
     * Telegram's in-call notification has neither the call category nor the
     * call style, so its calls are known by the fixed IDs its VoIPService
     * posts them under (201 ongoing, 202 incoming).
     */
    private val TELEGRAM_CALL_NOTIFICATION_IDS = setOf(201, 202)

    /** Notification key to package, for the notifications that currently mean a meeting. */
    private val ongoing = ConcurrentHashMap<String, String>()

    fun displayName(packageName: String): String = MEETING_APPS[packageName] ?: packageName

    /**
     * A notification was posted or updated. It marks a meeting when it is
     * ongoing and a call. [template] is the notification's
     * [android.app.Notification.EXTRA_TEMPLATE]. An update that stops
     * qualifying ends that meeting.
     */
    fun onPosted(
        key: String,
        packageName: String,
        isOngoing: Boolean,
        category: String? = null,
        notificationId: Int? = null,
        template: String? = null,
    ) {
        if (packageName !in MEETING_APPS) return
        val meansMeeting = isOngoing && isCall(packageName, category, notificationId, template)
        if (meansMeeting) ongoing[key] = packageName else ongoing.remove(key)
    }

    private fun isCall(
        packageName: String,
        category: String?,
        notificationId: Int?,
        template: String?,
    ): Boolean =
        category == CALL_CATEGORY ||
            template == CALL_STYLE_TEMPLATE ||
            (packageName == TELEGRAM && notificationId in TELEGRAM_CALL_NOTIFICATION_IDS)

    fun onRemoved(key: String) {
        ongoing.remove(key)
    }

    /** Rebuilds the state from the listener's active notifications after it (re)connects. */
    fun replaceAll(active: List<ActiveNotification>) {
        ongoing.clear()
        active.forEach { onPosted(it.key, it.packageName, it.isOngoing, it.category, it.notificationId, it.template) }
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
        val category: String? = null,
        val notificationId: Int? = null,
        val template: String? = null,
    )

    /** [android.app.Notification.CATEGORY_CALL], kept as a literal so this object stays JVM-testable. */
    private const val CALL_CATEGORY = "call"

    /** The template name of [android.app.Notification.CallStyle], a literal because the class is missing below Android 12. */
    internal const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"
    private const val TELEGRAM = "org.telegram.messenger"
}
