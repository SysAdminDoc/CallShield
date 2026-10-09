package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.checker.HeuristicChecker
import com.sysadmindoc.callshield.data.remote.CommunityWatchNumber
import com.sysadmindoc.callshield.service.overlayReasonLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The community watch list is a label, never a block: numbers other users
 * reported that haven't cleared the corroboration gate add a small score.
 */
@RunWith(RobolectricTestRunner::class)
class CommunityWatchScoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val heuristics = SpamHeuristics()

    @Test
    fun `a watch-list number alone stays under the aggressive block threshold`() {
        val number = "+573001234567"
        val before = heuristics.analyze(context, number)
        heuristics.updateCommunityWatch(listOf(CommunityWatchNumber(number, 50)))
        val after = heuristics.analyze(context, number)

        assertEquals(0, before.score)
        assertTrue(after.reasons.toString(), "community_watch" in after.reasons)
        assertTrue(after.score > before.score)
        assertTrue(after.score.toString(), after.score < HeuristicChecker.AGGRESSIVE_BLOCK_THRESHOLD)
    }

    @Test
    fun `one reporter or a number off the list gets no watch signal`() {
        heuristics.updateCommunityWatch(
            listOf(
                CommunityWatchNumber("+573001234567", 1),
                CommunityWatchNumber("+573009999999", 2),
            ),
        )

        assertEquals(0, heuristics.communityWatchReporterCount(context, "+573001234567"))
        assertEquals(2, heuristics.communityWatchReporterCount(context, "+573009999999"))
        assertFalse("community_watch" in heuristics.analyze(context, "+573001234567").reasons)
        assertFalse("community_watch" in heuristics.analyze(context, "+573005550000").reasons)
    }

    @Test
    fun `the popup and block descriptions name the signal`() {
        assertEquals(R.string.overlay_reason_community_watch, overlayReasonLabelRes("community_watch"))
        assertEquals(
            "Reported by 2 CallShield users, not confirmed",
            context.resources.getQuantityString(R.plurals.community_watch_label, 2, 2),
        )
    }
}
