package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.remote.ExternalLookup
import com.sysadmindoc.callshield.data.remote.RemoteLookupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the caller ID overlay shows while the live lookups answer: the running
 * tally across sources, and the header, status, colors and marks it picks.
 */
class CallerIdOverlayLiveLookupTest {
    private val palette =
        CallerIdOverlayPalette(
            background = 1,
            surfaceVariant = 2,
            primary = 3,
            error = 4,
            blue = 5,
            warning = 6,
            peach = 7,
            lavender = 8,
            text = 9,
            subtext = 10,
            overlay = 11,
            isLight = false,
        )

    private fun result(
        status: RemoteLookupStatus,
        isSpam: Boolean = false,
        reports: Int = 0,
    ) = ExternalLookup.SourceResult(source = "Source", isSpam = isSpam, reports = reports, status = status)

    @Test
    fun `the tally adds reports and counts FOUND and CLEAN as definitive answers`() {
        val tally = LiveLookupTally()

        assertEquals(LiveLookupSnapshot(1, 4, true, 1), tally.record(result(RemoteLookupStatus.FOUND, isSpam = true, reports = 4)))
        assertEquals(LiveLookupSnapshot(2, 4, true, 1), tally.record(null))
        assertEquals(LiveLookupSnapshot(3, 4, true, 1), tally.record(result(RemoteLookupStatus.TIMEOUT)))
        assertEquals(LiveLookupSnapshot(4, 6, true, 2), tally.record(result(RemoteLookupStatus.CLEAN, reports = 2)))
        assertEquals(LiveLookupSnapshot(5, 6, true, 2, uncategorized = true), tally.record(result(RemoteLookupStatus.UNCATEGORIZED)))
    }

    @Test
    fun `only the first fast hit is shown, and none after a source said spam`() {
        val tally = LiveLookupTally()
        assertTrue(tally.claimWarmHit())
        assertFalse(tally.claimWarmHit())

        val afterSpam = LiveLookupTally()
        afterSpam.record(result(RemoteLookupStatus.FOUND, isSpam = true))
        assertFalse(afterSpam.claimWarmHit())
    }

    @Test
    fun `the finished status says uncategorized only when nothing else was found`() {
        assertEquals(
            R.string.remote_lookup_status_uncategorized,
            overlayFinishedStatusRes(LiveLookupSnapshot(3, 0, false, 1, uncategorized = true)),
        )
        assertEquals(
            R.string.overlay_status_complete,
            overlayFinishedStatusRes(LiveLookupSnapshot(3, 2, false, 1, uncategorized = true)),
        )
        assertEquals(
            R.string.overlay_status_complete,
            overlayFinishedStatusRes(LiveLookupSnapshot(3, 0, true, 1, uncategorized = true)),
        )
        assertEquals(R.string.detail_no_definitive_source_result, overlayFinishedStatusRes(LiveLookupSnapshot(3, 0, false, 0)))
    }

    @Test
    fun `the header and color follow the live score`() {
        assertEquals(R.string.overlay_header_likely_spam, overlayScoredHeaderRes(50))
        assertEquals(R.string.overlay_header_suspicious, overlayScoredHeaderRes(49))
        assertEquals(R.string.overlay_header_suspicious, overlayScoredHeaderRes(1))
        assertEquals(R.string.overlay_header_safe, overlayScoredHeaderRes(0))

        assertEquals(palette.error, overlayScoreColor(70, palette))
        assertEquals(palette.peach, overlayScoreColor(69, palette))
        assertEquals(palette.peach, overlayScoreColor(40, palette))
        assertEquals(palette.warning, overlayScoreColor(39, palette))
        assertEquals(palette.warning, overlayScoreColor(1, palette))
        assertEquals(palette.primary, overlayScoreColor(0, palette))
    }

    @Test
    fun `before any lookup answers, an outgoing warning wins over a local verdict`() {
        assertEquals(R.string.overlay_header_outgoing_risk, overlayInitialHeaderRes(outgoingRiskWarning = true, confidence = 80))
        assertEquals(R.string.overlay_header_possible_spam, overlayInitialHeaderRes(outgoingRiskWarning = false, confidence = 1))
        assertEquals(R.string.overlay_header_incoming_call, overlayInitialHeaderRes(outgoingRiskWarning = false, confidence = 0))

        assertEquals(R.string.overlay_outgoing_risk_status, overlayInitialStatusRes(outgoingRiskWarning = true, liveEnrichmentEnabled = true))
        assertEquals(R.string.overlay_status_querying, overlayInitialStatusRes(outgoingRiskWarning = false, liveEnrichmentEnabled = true))
        assertEquals(R.string.overlay_status_local_only, overlayInitialStatusRes(outgoingRiskWarning = false, liveEnrichmentEnabled = false))
    }

    @Test
    fun `a source row is marked and colored by what that source said`() {
        val warning = String(Character.toChars(0x26A0))
        val check = String(Character.toChars(0x2713))

        assertEquals(warning, overlaySourceIcon(result(RemoteLookupStatus.FOUND, isSpam = true)))
        assertEquals("!", overlaySourceIcon(result(RemoteLookupStatus.UNCATEGORIZED)))
        assertEquals("!", overlaySourceIcon(result(RemoteLookupStatus.RATE_LIMITED)))
        assertEquals(check, overlaySourceIcon(result(RemoteLookupStatus.CLEAN)))
        assertEquals(check, overlaySourceIcon(result(RemoteLookupStatus.FOUND)))

        assertEquals(palette.error, overlaySourceColor(result(RemoteLookupStatus.FOUND, isSpam = true), palette))
        assertEquals(palette.overlay, overlaySourceColor(result(RemoteLookupStatus.UNCATEGORIZED), palette))
        assertEquals(palette.overlay, overlaySourceColor(result(RemoteLookupStatus.OFFLINE_MODE), palette))
        assertEquals(palette.primary, overlaySourceColor(result(RemoteLookupStatus.CLEAN), palette))
    }
}
