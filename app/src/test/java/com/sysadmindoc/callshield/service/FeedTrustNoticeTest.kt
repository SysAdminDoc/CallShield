package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.data.repository.feedTrustFailureFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update notice for a certificate pin failure fires once per app version:
 * enough that someone running a broken build hears about it, and quiet after
 * that, because retrying a sync cannot fix it.
 */
class FeedTrustNoticeTest {
    private val failedAt = 1_788_609_713_867L

    @Test
    fun `no recorded failure means no notice`() {
        assertFalse(FeedTrustNotice.shouldNotify(failedAt = 0L, notifiedVersion = null, currentVersion = 67))
        assertFalse(FeedTrustNotice.shouldNotify(failedAt = 0L, notifiedVersion = 66, currentVersion = 67))
    }

    @Test
    fun `a failure this version has not announced yet is announced`() {
        assertTrue(FeedTrustNotice.shouldNotify(failedAt, notifiedVersion = null, currentVersion = 67))
    }

    @Test
    fun `the same version never announces twice`() {
        assertFalse(FeedTrustNotice.shouldNotify(failedAt, notifiedVersion = 67, currentVersion = 67))
    }

    @Test
    fun `a newer build that fails again announces again`() {
        // The update the last notice asked for did not fix it, so say so once more.
        assertTrue(FeedTrustNotice.shouldNotify(failedAt, notifiedVersion = 66, currentVersion = 67))
    }

    @Test
    fun `a trust failure only counts for the build that recorded it`() {
        assertEquals(failedAt, feedTrustFailureFor(failedAt, failedVersion = 66, currentVersion = 66))
        // The update installed fixed pins that have not been tried yet.
        assertEquals(0L, feedTrustFailureFor(failedAt, failedVersion = 66, currentVersion = 67))
        // A record written before versions were stored cannot be attributed.
        assertEquals(0L, feedTrustFailureFor(failedAt, failedVersion = null, currentVersion = 67))
        assertEquals(0L, feedTrustFailureFor(failedAt = null, failedVersion = 67, currentVersion = 67))
    }

    @Test
    fun `the notice points at the project's own releases page`() {
        assertEquals("https://github.com/SysAdminDoc/CallShield/releases/latest", FeedTrustNotice.RELEASES_URL)
    }
}
