package com.sysadmindoc.callshield

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.telecom.Connection
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.BlockReasoning
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckContext
import com.sysadmindoc.callshield.data.checker.EmergencyNumberFloorChecker
import com.sysadmindoc.callshield.data.checker.RegulatoryAllowChecker
import com.sysadmindoc.callshield.data.checker.RegulatoryPrefixChecker
import com.sysadmindoc.callshield.data.checker.SmsContentChecker
import com.sysadmindoc.callshield.data.checker.StirShakenChecker
import com.sysadmindoc.callshield.data.checker.TimeBlockChecker
import com.sysadmindoc.callshield.data.checker.VerificationMessageFloorChecker
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.data.remote.UrlThreatCategory
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.DnoStatus
import com.sysadmindoc.callshield.domain.model.LineType
import com.sysadmindoc.callshield.domain.model.ParsedPassport
import com.sysadmindoc.callshield.domain.model.RichCallData
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.service.CallShieldTileService
import com.sysadmindoc.callshield.service.RcsNotificationListener
import com.sysadmindoc.callshield.ui.describeIdentityEvidence
import com.sysadmindoc.callshield.ui.pipelineCheckerLabelRes
import com.sysadmindoc.callshield.ui.urlThreatLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Text the system shows (Settings labels, block descriptions, the digest)
 * has to come from resources to follow the app language. Run in Chinese, so a
 * hard-coded English literal can't pass for a resource that happens to match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class LocalizedSystemTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ctx(
        number: String = "+12122340101",
        smsBody: String? = null,
        prefs: Preferences = emptyPreferences(),
        verificationStatus: Int? = null,
    ) = CheckContext(
        appContext = context,
        number = number,
        smsBody = smsBody,
        realtimeCall = true,
        prefs = prefs,
        verificationStatus = verificationStatus,
    )

    @Test
    fun `the tile and notification-listener labels are resources`() {
        val pm = context.packageManager
        val listener = pm.getServiceInfo(ComponentName(context, RcsNotificationListener::class.java), 0)
        val tile = pm.getServiceInfo(ComponentName(context, CallShieldTileService::class.java), 0)

        assertEquals(R.string.notification_listener_label, listener.labelRes)
        assertEquals(R.string.app_name, tile.labelRes)
        assertEquals(context.getString(R.string.notification_listener_label), listener.loadLabel(pm).toString())
        assertNotEquals("CallShield RCS Filter", listener.loadLabel(pm).toString())
    }

    @Test
    fun `checker descriptions follow the app language`() =
        runBlocking {
            val quietHours =
                preferencesOf(
                    SpamRepository.KEY_TIME_BLOCK to true,
                    SpamRepository.KEY_TIME_BLOCK_START to 8,
                    SpamRepository.KEY_TIME_BLOCK_END to 8,
                )

            @Suppress("DEPRECATION")
            val failed = Connection.VERIFICATION_STATUS_FAILED

            assertEquals(
                context.getString(R.string.block_reason_quiet_hours),
                TimeBlockChecker().check(ctx(prefs = quietHours))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_carrier_unverified),
                StirShakenChecker().check(ctx(verificationStatus = failed))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_emergency_floor),
                EmergencyNumberFloorChecker().check(ctx(number = "911"))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_verification_floor),
                VerificationMessageFloorChecker(SmsContentAnalyzer())
                    .check(ctx(smsBody = "Your verification code is 482913."))
                    ?.description,
            )
            assertNotEquals("Blocked during quiet hours", context.getString(R.string.block_reason_quiet_hours))
        }

    @Test
    fun `signal descriptions follow the app language`() =
        runBlocking {
            val sms =
                SmsContentChecker(SmsContentAnalyzer())
                    .check(ctx(smsBody = "Congratulations, you won a prize! Claim it now at bit.ly/x9Qk2"))
            val description = requireNotNull(sms).description

            assertEquals(listOf("shortened_url", "spam_keywords"), sms.signals)
            assertTrue(description.contains(context.getString(R.string.overlay_reason_risky_link)))
            assertTrue(description.contains(context.getString(R.string.overlay_reason_spam_keywords)))
            assertFalse("raw token in \"$description\"", description.contains("shortened") || description.contains("keywords"))
            // The "why" panel still gets one bullet per signal from a Chinese list.
            val bullets = BlockReasoning.explain(context, "sms_content", description, sms.confidence).bullets
            assertEquals(2, bullets.count { it.startsWith("• ") })
        }

    @Test
    fun `a heuristic block through the pipeline is described in the app language`() =
        runBlocking {
            val fixture = IsolatedRepositoryFixture(context)
            try {
                // Turks and Caicos: a one-ring callback-scam area code and nothing else.
                val result = fixture.repository.isSpam("+16495550123")

                assertEquals("heuristic", result.matchSource)
                assertEquals(listOf("wangiri_country"), result.signals)
                assertEquals(context.getString(R.string.overlay_reason_wangiri), result.description)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `the screened-message notification gives its reason in the app language`() {
        val verdict = RcsNotificationListener.contentVerdict("Claim your prize now at bit.ly/example", enabled = true, aggressive = true)
        val fromContent = RcsNotificationListener.screenedMessageReason(context, null, verdict)
        val fromPipeline =
            RcsNotificationListener.screenedMessageReason(context, SpamCheckResult(isSpam = true, matchSource = "database"), null)

        assertTrue(fromContent.contains(context.getString(R.string.overlay_reason_risky_link)))
        assertFalse(fromContent.contains("shortened"))
        assertEquals(context.getString(R.string.stats_reason_spam_database), fromPipeline)
        assertNotEquals("database", fromPipeline)
    }

    @Test
    fun `identity evidence in an ML block is worded in the app language`() {
        val identity =
            CallerIdentity(
                verificationStatus = 0,
                passport = passport("B").copy(richCallData = RichCallData(name = "Bank")),
                dnoStatus = DnoStatus.UNASSIGNED,
                lineType = LineType.PREMIUM_RATE,
            )
        val description = requireNotNull(describeIdentityEvidence(context, identity))

        assertEquals(
            "身份证据：PASSporT B 级认证元数据（运营商状态不是“通过”）；来电号码尚未分配；线路类型：付费电话；富来电数据元数据（并非垃圾来电判定）",
            description,
        )
        for (english in listOf("Identity evidence", "metadata", "origin", "line type", "verdict", "carrier status")) {
            assertFalse("\"$english\" in $description", description.contains(english, ignoreCase = true))
        }
    }

    @Test
    fun `the why-was-this-blocked panel has no English word in Chinese`() {
        // Names and acronyms the translation keeps. Any other Latin word, even a
        // single one like "range" or "spam", is English leaking into the panel.
        val kept = setOf("Android", "CallShield", "PASSporT", "VoIP")

        fun englishWords(text: String) =
            Regex("[A-Za-z]+")
                .findAll(text)
                .map { it.value }
                .filterNot { it in kept || it.all(Char::isUpperCase) }
                .toList()

        // A Chinese description renders the labeled bullets (note, range, rule)
        // without adding English of its own.
        val description = "测试"
        val explanations =
            BlockReasonCode.entries.flatMap { code ->
                listOf("", description).map { BlockReasoning.explain(context, reasonCode = code, description = it, confidence = 50) }
            } +
                BlockReasoning.explain(context, "category_policy:scam:silence:database", description, 100) +
                BlockReasoning.explain(context, "rcs_database", description, 100) +
                BlockReasoning.explain(context, "", "", 0)

        for (reasoning in explanations) {
            val text = (listOf(reasoning.headline) + reasoning.bullets).joinToString("\n")
            assertEquals(text, emptyList<String>(), englishWords(text))
            assertTrue(text, text.any { it in '一'..'鿿' })
        }
        assertTrue(explanations.any { reasoning -> reasoning.bullets.any { it.contains(description) } })
        assertEquals(
            context.getString(R.string.reasoning_category_silenced, context.getString(R.string.call_category_scam)),
            explanations[explanations.size - 3].headline,
        )
        // The detector itself flags a lone English word.
        assertEquals(listOf("range"), englishWords("号段：range"))
    }

    @Test
    fun `every checker has a Chinese name of its own`() =
        runBlocking {
            val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            // Every checker the call chain runs, from a trace, so a new checker is covered.
            val traced =
                IsolatedRepositoryFixture(context).use {
                    it.repository
                        .traceRules("+12122340101")
                        .entries
                        .map { entry -> entry.checkerName }
                }
            assertTrue(traced.toString(), traced.size >= 30)
            traced.forEach { name -> assertNotEquals(name, R.string.lookup_checker_other, pipelineCheckerLabelRes(name)) }
            // And every checker name the app has, the SMS-only ones included.
            val names =
                R.string::class.java.fields
                    .filter { it.name.startsWith("lookup_checker_") }
                    .map { it.getInt(null) }
            assertTrue(traced.map(::pipelineCheckerLabelRes).toSet().all { it in names })

            for (res in names) {
                val zh = context.getString(res)
                assertNotEquals(context.resources.getResourceEntryName(res), english.getString(res), zh)
                assertTrue(zh, zh.any { it in '一'..'鿿' })
            }
        }

    @Test
    fun `a regulatory range decision names the range in the app language`() =
        runBlocking {
            val block =
                requireNotNull(
                    RegulatoryPrefixChecker().check(ctx(number = "+34400123456", prefs = preferencesOf(SpamRepository.KEY_REG_SPAIN_400 to true))),
                )
            val allow =
                requireNotNull(
                    RegulatoryAllowChecker().check(
                        ctx(number = "+911600123456", prefs = preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                    ),
                )

            assertEquals(context.getString(R.string.reg_prefix_spain_400_match), block.description)
            assertEquals(context.getString(R.string.reg_prefix_india_1600_allow_match), allow.description)
            assertFalse(block.description, block.description.contains("Spain"))
            assertFalse(allow.description, allow.description.contains("India"))
            val panel = BlockReasoning.explain(context, block.matchSource, block.description, block.confidence)
            assertTrue(panel.bullets.toString(), panel.bullets.any { it.contains(block.description) })
        }

    @Test
    fun `unsafe links are named by kind, never by a feed's code`() {
        fun link(
            threat: String,
            category: UrlThreatCategory = UrlThreatCategory.UNKNOWN,
        ) = UrlSafetyChecker.UrlCheckResult(url = "https://$threat.example", isMalicious = true, threat = threat, category = category)

        val labels =
            urlThreatLabels(
                context,
                listOf(
                    link("verified_phish", UrlThreatCategory.PHISHING),
                    link(UrlSafetyChecker.KNOWN_SPAM_DOMAIN_THREAT),
                    link("malware_download", UrlThreatCategory.MALWARE),
                    link("feed_domain_match"),
                    link("unverified_phish", UrlThreatCategory.PHISHING),
                ),
            )

        assertEquals("钓鱼、垃圾信息、恶意软件、危险", labels)
    }

    private fun passport(attestation: String) =
        ParsedPassport(
            typ = "passport",
            algorithm = "ES256",
            certificateUrl = "https://example.com/cert",
            issuedAtEpochSeconds = 1_700_000_000L,
            originTelephoneNumber = "+12125550100",
            destinationTelephoneNumbers = listOf("+12125550101"),
            destinationUris = emptyList(),
            attestation = attestation,
        )

    @Test
    fun `the Chinese digest keeps its line breaks`() {
        // A literal line break inside a string resource collapses to a space,
        // which ran the whole Chinese digest together on one line.
        assertEquals(3, context.getString(R.string.digest_big_text, 3, 2, 1, "x").lines().size)
    }
}
