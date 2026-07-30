package com.sysadmindoc.callshield.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test fun `report normalization strips national trunk prefixes`() {
        // Reported via GitHub issue #6 in the domestic dialling form. Calls arrive
        // canonicalized as +865586468536, so the trunk 0 must not reach the database.
        assertEquals("+865586468536", CommunityContributor.normalizeForReport("+86 0558 646 8536"))
        assertEquals("+442071234567", CommunityContributor.normalizeForReport("+44 (0)20 7123 4567"))
        assertEquals("+4930123456", CommunityContributor.normalizeForReport("+49 030 123456"))
    }

    @Test fun `report normalization keeps the leading zero Italy actually uses`() {
        assertEquals("+390612345678", CommunityContributor.normalizeForReport("+39 06 1234 5678"))
    }

    @Test fun `report normalization leaves E164 numbers untouched`() {
        assertEquals("+865586468536", CommunityContributor.normalizeForReport("+865586468536"))
        assertEquals("+12122345678", CommunityContributor.normalizeForReport("+1 212 234 5678"))
    }

    @Test fun `trunk prefix stripping tolerates degenerate input`() {
        assertEquals("", CommunityContributor.stripNationalTrunkPrefix(""))
        assertEquals("86", CommunityContributor.stripNationalTrunkPrefix("86"))
        assertEquals("86000", CommunityContributor.stripNationalTrunkPrefix("86000"))
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

    @Test fun `report normalization agrees with the worker and pipeline normalizers`() {
        // scripts/normalizer_fixtures.json is the single truth table for all
        // three implementations of this normalizer (Kotlin here, JavaScript in
        // worker/community-reports-worker.js, Python in
        // scripts/phone_normalization.py). Each suite asserts its own column, so
        // a fix landed in one language and not the others fails the build rather
        // than silently storing a key the other two can never produce.
        for (case in NormalizerFixtures.load()) {
            val actual = CommunityContributor.normalizeForReport(case.input)
            assertEquals("${case.input}: ${case.why}", case.expectedKotlin, actual)
        }
    }
}

private data class NormalizerFixtureCase(
    val input: String,
    val why: String,
    val expectedKotlin: String?,
)

private object NormalizerFixtures {
    private const val RELATIVE_PATH = "scripts/normalizer_fixtures.json"

    fun load(): List<NormalizerFixtureCase> {
        val file = locate()
        val moshi = Moshi.Builder().build()
        val root = moshi.adapter(Any::class.java).fromJson(file.readText())
        val cases = (root as Map<*, *>)["cases"] as List<*>
        return cases.map { entry ->
            val case = entry as Map<*, *>
            val expected = case["expected"] as Map<*, *>
            NormalizerFixtureCase(
                input = case["input"] as String,
                why = case["why"] as String,
                expectedKotlin = expected["kotlin"] as String?,
            )
        }
    }

    /** Walks up from the Gradle module dir so the test works from any working directory. */
    private fun locate(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, RELATIVE_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $RELATIVE_PATH walking up from ${File("").absolutePath}")
    }
}

private object CommunityContributorTestConstants {
    const val MAX_SMS_REPORT_DOMAINS = 10
}
