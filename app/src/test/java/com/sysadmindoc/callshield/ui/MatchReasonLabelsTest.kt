package com.sysadmindoc.callshield.ui

import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MatchReasonLabelsTest {
    @Test
    fun `every production checker has a dedicated localized label`() {
        val productionCheckerNames =
            listOf(
                "manual_whitelist",
                "contact_whitelist",
                "contacts_only",
                "stir_shaken_trusted",
                "stir_shaken_failed",
                "temporary_allow",
                "system_block_list",
                "user_blocklist",
                "database",
                "db_prefix_expansion",
                "prefix",
                "wildcard",
                "hash_wildcard",
                "recently_dialed",
                "answered_caller",
                "emergency_callback",
                "repeated_urgent",
                "caller_name_trust",
                "caller_name",
                "region_block",
                "campaign_recorder",
                "time_block",
                "frequency",
                "heuristic",
                "campaign_burst",
                "ml_scorer",
                "sms_context",
                "sms_burst",
                "keyword",
                "sms_content",
                "push_alert",
            )

        productionCheckerNames.forEach { name ->
            assertNotEquals(name, R.string.lookup_checker_other, pipelineCheckerLabelRes(name))
        }
    }

    @Test
    fun `unknown checker uses a neutral label rather than exposing its token`() {
        assertEquals(R.string.lookup_checker_other, pipelineCheckerLabelRes("experimental_checker_v2"))
    }

    @Test
    fun `all spam types produced by the checker chain have labels`() {
        val productionTypes =
            listOf(
                "spam",
                "robocall",
                "spoofed",
                "user_blocked",
                "caller_name",
                "out_of_region",
                "premium_scam",
                "wangiri_scam",
                "sms_spam",
                "phishing",
                "suspicious",
            )

        productionTypes.forEach { type ->
            assertNotEquals(type, null, spamTypeLabelRes(type))
        }
    }

    @Test
    fun `type mapping is case and whitespace tolerant`() {
        assertEquals(R.string.spam_type_robocall, spamTypeLabelRes("  ROBOCALL "))
    }

    @Test
    fun `every persisted reason has a plain spoken sentence`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jargon = listOf("attestation", "stir", "shaken", "rcs", "heuristic", " ml ", "layer")

        BlockReasonCode.entries.forEach { reasonCode ->
            val sentence = context.getString(blockReasonAccessibilityLabelRes(reasonCode))
            assertTrue(reasonCode.wireValue, sentence.endsWith('.'))
            assertFalse(
                "${reasonCode.wireValue}: $sentence",
                jargon.any { token -> sentence.lowercase().contains(token) },
            )
        }
    }
}
