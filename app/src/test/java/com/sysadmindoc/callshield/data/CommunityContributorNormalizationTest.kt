package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityContributorNormalizationTest {
    @Test fun `report normalization uses ASCII-only phone digits`() {
        assertEquals("+12125551234", CommunityContributor.normalizeForReport("+1 (212) 555-1234"))
    }

    @Test fun `report normalization rejects ambiguous national numbers`() {
        assertNull(CommunityContributor.normalizeForReport("212-555-1234"))
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

    @Test fun `SMS report JSON includes only body-free indicators`() {
        val json =
            CommunityContributor.buildReportJson(
                normalizedNumber = "+12125551234",
                type = "sms_spam",
                smsIndicators =
                    SmsContentAnalyzer.SmsReportIndicators(
                        domains = listOf("Bad.Example", "bad.example.", "invalid"),
                        urlIndicators = listOf("URL_PRESENT", "bad-path/secret"),
                    ),
            )

        assertTrue(json.contains(""""sms_domains":["bad.example"]"""))
        assertTrue(json.contains(""""sms_url_indicators":["url_present"]"""))
        assertFalse(json.contains("sms_body"))
        assertFalse(json.contains("body"))
        assertFalse(json.contains("secret"))
    }

    @Test fun `SMS report sanitizer limits malformed indicators`() {
        val sanitized =
            CommunityContributor.sanitizeSmsIndicators(
                SmsContentAnalyzer.SmsReportIndicators(
                    domains = (0..20).map { "spam$it.example/path" } + listOf("-bad.example"),
                    urlIndicators = (0..20).map { "url_present" } + listOf("bad/path"),
                ),
            )

        assertEquals(CommunityContributorTestConstants.MAX_SMS_REPORT_DOMAINS, sanitized.domains.size)
        assertEquals(listOf("url_present"), sanitized.urlIndicators)
        assertFalse(sanitized.domains.any { "/" in it })
    }
}

private object CommunityContributorTestConstants {
    const val MAX_SMS_REPORT_DOMAINS = 10
}
