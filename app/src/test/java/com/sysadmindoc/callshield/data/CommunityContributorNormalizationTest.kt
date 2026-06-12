package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityContributorNormalizationTest {
    @Test fun `report normalization uses ASCII-only phone digits`() {
        assertEquals("+12125551234", CommunityContributor.normalizeForReport("+1 (212) 555-1234"))
    }

    @Test fun `report normalization adds US country code for ten digit numbers`() {
        assertEquals("+12125551234", CommunityContributor.normalizeForReport("212-555-1234"))
    }

    @Test fun `report normalization strips direction and zero-width marks`() {
        val payload = "\u200E+\u200F1 212\u200B-555\u200E-1234"
        assertEquals("+12125551234", CommunityContributor.normalizeForReport(payload))
    }

    @Test fun `report normalization rejects Arabic Indic digits`() {
        val arabicIndic = "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660"
        assertNull(CommunityContributor.normalizeForReport(arabicIndic))
    }

    @Test fun `report normalization rejects fullwidth digits`() {
        val fullwidth = "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10"
        assertNull(CommunityContributor.normalizeForReport(fullwidth))
    }

    @Test fun `report normalization rejects overlong numbers`() {
        assertNull(CommunityContributor.normalizeForReport("+1234567890123456"))
    }
}
