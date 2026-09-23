package com.sysadmindoc.callshield.data.checker

import android.content.Context
import com.sysadmindoc.callshield.data.MeetingModeRegistry
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository

/**
 * Meeting mode: silences a call from outside the user's contacts while one of
 * the meeting apps they picked holds an ongoing notification (see
 * [MeetingModeRegistry]). It rides on the notification access CallShield
 * already has, so there's no calendar permission.
 *
 * It runs last, below every allow and every spam layer, so it only catches a
 * call that would otherwise ring: spam still gets its own verdict and a
 * trusted caller still gets through. The screening service delivers it as a
 * silence, never a reject, so the caller reaches voicemail and Android still
 * shows the missed call.
 */
internal class MeetingModeChecker(
    private val appContext: Context,
    private val spamHeuristics: SpamHeuristics,
    private val activeMeetingApps: () -> Set<String> = MeetingModeRegistry::activePackages,
    private val isContact: (Context, String) -> Boolean = { context, number -> spamHeuristics.isInContacts(context, number) },
) : IChecker {
    override val priority = CheckerPriority.MEETING_MODE
    override val name = MATCH_SOURCE

    override suspend fun isEnabled(ctx: CheckContext): Boolean =
        ctx.realtimeCall &&
            ctx.smsBody == null &&
            (ctx.prefs[SpamRepository.KEY_MEETING_MODE] ?: false) &&
            !ctx.prefs[SpamRepository.KEY_MEETING_MODE_APPS].isNullOrEmpty()

    override suspend fun check(ctx: CheckContext): BlockResult? {
        val selected = ctx.prefs[SpamRepository.KEY_MEETING_MODE_APPS].orEmpty()
        val meetingApp = activeMeetingApps().firstOrNull { it in selected } ?: return null
        if (isContact(appContext, ctx.number)) return null
        return BlockResult.block(
            matchSource = MATCH_SOURCE,
            description = MeetingModeRegistry.displayName(meetingApp),
        )
    }

    companion object {
        const val MATCH_SOURCE = "meeting_mode"
    }
}
