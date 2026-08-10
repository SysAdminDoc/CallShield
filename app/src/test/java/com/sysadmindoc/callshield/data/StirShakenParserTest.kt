package com.sysadmindoc.callshield.data

import android.os.Bundle
import com.sysadmindoc.callshield.domain.model.DnoStatus
import com.sysadmindoc.callshield.domain.model.LineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class StirShakenParserTest {
    private val now = 1_700_000_000L
    private val origid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `valid A PASSporT retains bounded claims and RCD`() {
        val result =
            StirShakenParser.parse(
                token(
                    payload =
                        """
                        {
                          "iat": 1700000000,
                          "orig": {"tn": "+12125550100"},
                          "dest": {"tn": ["+12125550101"], "uri": ["sip:dest@example.com"]},
                          "attest": "A",
                          "origid": "$origid",
                          "mky": [{"alg": "ES256"}],
                          "rcd": {
                            "nam": "Example Bank",
                            "apn": "+12125550102",
                            "icn": "https://example.com/icon.png",
                            "jcd": {"fn": "Example Bank"},
                            "jcl": "https://example.com/contact.json"
                          }
                        }
                        """.trimIndent(),
                ),
                now,
            )

        val passport = accepted(result)
        assertEquals("passport", passport.typ)
        assertEquals("ES256", passport.algorithm)
        assertEquals("https://example.com/cert", passport.certificateUrl)
        assertEquals("+12125550100", passport.originTelephoneNumber)
        assertEquals(listOf("+12125550101"), passport.destinationTelephoneNumbers)
        assertEquals(listOf("sip:dest@example.com"), passport.destinationUris)
        assertEquals("A", passport.attestation)
        assertEquals(origid, passport.origid)
        assertEquals(1, passport.mediaKeyCount)
        assertEquals("Example Bank", passport.richCallData?.name)
        assertEquals("https://example.com/icon.png", passport.richCallData?.iconUrl)
        assertTrue(passport.richCallData?.inlineJCardPresent == true)
        assertEquals("https://example.com/contact.json", passport.richCallData?.jCardUrl)
        assertTrue(passport.signaturePresent)
    }

    @Test
    fun `B C and missing attestation are represented without a trust verdict`() {
        listOf("B", "C", null).forEach { attestation ->
            val claim = attestation?.let { "\"attest\": \"$it\"," }.orEmpty()
            val passport =
                accepted(
                    StirShakenParser.parse(
                        token(
                            payload =
                                """
                                {"iat":1700000000,"orig":{"tn":"+12125550100"},"dest":{"tn":["+12125550101"]},$claim"origid":"$origid"}
                                """.trimIndent(),
                        ),
                        now,
                    ),
                )
            assertEquals(attestation, passport.attestation)
        }
    }

    @Test
    fun `malformed token headers and claims fail closed`() {
        assertRejected(
            StirShakenParser.parse("not-a-jwt", now),
            StirShakenRejectionReason.MALFORMED_JWT,
        )
        assertRejected(
            StirShakenParser.parse(
                token(
                    header = """{"typ":"passport","alg":"HS256","x5u":"https://example.com/cert"}""",
                ),
                now,
            ),
            StirShakenRejectionReason.UNSUPPORTED_HEADER,
        )
        assertRejected(
            StirShakenParser.parse(
                token(
                    header = """{"typ":"passport","alg":"ES256"}""",
                ),
                now,
            ),
            StirShakenRejectionReason.UNSUPPORTED_HEADER,
        )
        assertRejected(
            StirShakenParser.parse(
                token(signature = ""),
                now,
            ),
            StirShakenRejectionReason.MALFORMED_JWT,
        )
        assertRejected(
            StirShakenParser.parse(
                token(payload = """{"iat":1700000000,"orig":{"tn":"+12125550100"},"dest":{"tn":["+12125550101"]},"origid":"not-a-uuid"}"""),
                now,
            ),
            StirShakenRejectionReason.INVALID_CLAIM,
        )
    }

    @Test
    fun `iat must be within sixty seconds in either direction`() {
        val old = validPayload(iat = now - 61)
        val future = validPayload(iat = now + 61)

        assertRejected(StirShakenParser.parse(token(payload = old), now), StirShakenRejectionReason.STALE_IAT)
        assertRejected(StirShakenParser.parse(token(payload = future), now), StirShakenRejectionReason.STALE_IAT)
        assertTrue(StirShakenParser.parse(token(payload = validPayload(iat = now + 60)), now) is StirShakenParseResult.Accepted)
    }

    @Test
    fun `RCD URLs are HTTPS-only and tokens are bounded`() {
        val badIcon =
            validPayload(
                rcd = """"rcd":{"nam":"Bank","icn":"http://example.com/icon"},""",
            )
        assertRejected(
            StirShakenParser.parse(token(payload = badIcon), now),
            StirShakenRejectionReason.INVALID_CLAIM,
        )
        assertRejected(
            StirShakenParser.parse("a".repeat(StirShakenParser.MAX_TOKEN_LENGTH + 1), now),
            StirShakenRejectionReason.TOO_LARGE,
        )
    }

    @Test
    fun `allowlisted telecom extras provide optional identity signals`() {
        val extras =
            Bundle().apply {
                putString(StirShakenParser.EXTRA_PASSPORT, token(payload = validPayload()))
                putString(StirShakenParser.EXTRA_DNO_STATUS, "do-not-originate")
                putString(StirShakenParser.EXTRA_LINE_TYPE, "prepaid")
            }

        val parsed = StirShakenParser.readExtras(extras, now)

        assertNotNull(parsed.passport)
        assertEquals(DnoStatus.LISTED, parsed.dnoStatus)
        assertEquals(LineType.PREPAID, parsed.lineType)
    }

    private fun validPayload(
        iat: Long = now,
        rcd: String = "",
    ): String =
        """
        {"iat":$iat,"orig":{"tn":"+12125550100"},"dest":{"tn":["+12125550101"]},$rcd"origid":"$origid"}
        """.trimIndent()

    private fun token(
        header: String = """{"typ":"passport","alg":"ES256","x5u":"https://example.com/cert"}""",
        payload: String = validPayload(),
        signature: String = "signature",
    ): String = listOf(header, payload, signature).joinToString(".") { encode(it) }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun accepted(result: StirShakenParseResult): com.sysadmindoc.callshield.domain.model.ParsedPassport {
        assertTrue("Expected accepted PASSporT but got $result", result is StirShakenParseResult.Accepted)
        return (result as StirShakenParseResult.Accepted).passport
    }

    private fun assertRejected(
        result: StirShakenParseResult,
        reason: StirShakenRejectionReason,
    ) {
        assertFalse("Expected rejected PASSporT but got $result", result is StirShakenParseResult.Accepted)
        assertEquals(reason, (result as StirShakenParseResult.Rejected).reason)
    }
}
