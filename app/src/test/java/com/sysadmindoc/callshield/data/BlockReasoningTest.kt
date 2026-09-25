package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.checker.CheckerPriority
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

private const val EN_DASH = 0x2013
private const val EM_DASH = 0x2014

// Robolectric because every sentence now comes from string resources.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockReasoningTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `only probabilistic layers expose confidence as a probability`() {
        assertTrue(BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.HEURISTIC))
        assertTrue(BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.CAMPAIGN_BURST))
        assertTrue(BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.ML_SCORER))
        assertTrue(BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.SMS_CONTENT))
        assertTrue(!BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.USER_BLOCKLIST))
        assertTrue(!BlockReasoning.isProbabilistic(com.sysadmindoc.callshield.domain.model.BlockReasonCode.DATABASE))
    }

    @Test
    fun `user rule classification is limited to reversible saved blocks`() {
        assertTrue(BlockReasoning.isUserRule(com.sysadmindoc.callshield.domain.model.BlockReasonCode.USER_BLOCKLIST))
        assertTrue(BlockReasoning.isUserRule(com.sysadmindoc.callshield.domain.model.BlockReasonCode.TEMPORARY_BLOCK))
        assertTrue(!BlockReasoning.isUserRule(com.sysadmindoc.callshield.domain.model.BlockReasonCode.DATABASE))
    }

    @Test
    fun `user_blocklist explanation names the personal blocklist and includes note`() {
        val r = BlockReasoning.explain(context, "user_blocklist", "Spammer, blocked manually", 100)
        assertTrue(r.headline.contains("You blocked"))
        assertTrue(r.bullets.any { it.contains("personal blocklist") })
        assertTrue(r.bullets.any { it.contains("Spammer") })
    }

    @Test
    fun `every reason code but UNKNOWN has its own explanation`() {
        BlockReasonCode.entries.filter { it != BlockReasonCode.UNKNOWN }.forEach { code ->
            val r = BlockReasoning.explain(context, reasonCode = code, description = "", confidence = 50)
            assertNotEquals(code.wireValue, context.getString(R.string.reasoning_unrecognized), r.headline)
        }
    }

    @Test
    fun `no explanation cites a layer number or uses a dash as punctuation`() {
        // The ladder uses priorities, and old "layer 5" style numbers no longer match anything.
        val staleLayer = Regex("""layer \d""")
        val dashes = setOf(EN_DASH, EM_DASH)
        BlockReasonCode.entries.forEach { code ->
            val r = BlockReasoning.explain(context, reasonCode = code, description = "", confidence = 50)
            val text = (listOf(r.headline) + r.bullets).joinToString("\n")
            assertFalse("${code.wireValue}: $text", staleLayer.containsMatchIn(text))
            assertFalse("${code.wireValue}: $text", text.any { it.code in dashes })
        }
    }

    @Test
    fun `regulatory prefix explanation names the matched range`() {
        val r = BlockReasoning.explain(context, "regulatory_prefix", "Spain 400 commercial call range", 100)
        assertTrue(r.headline.contains("telemarketing range"))
        assertTrue(r.bullets.any { it.contains("Spain 400 commercial call range") })
    }

    @Test
    fun `regulatory allow explanation names what it overrides and what still wins`() {
        // REGULATORY_ALLOW (5250) sits above quiet hours and region rules but
        // below the blocklist, wildcard, range and Android block-list layers.
        val r = BlockReasoning.explain(context, "regulatory_allow", "", 100)
        assertTrue(r.headline.contains("regulator protects"))
        assertTrue(r.bullets.any { it.contains("quiet hours and region rules") })
        assertTrue(r.bullets.any { it.contains("downloaded prefix list still win") })
    }

    @Test
    fun `each allow names the layers that outrank it on the priority ladder`() {
        val allows =
            mapOf(
                BlockReasonCode.TEMPORARY_ALLOW to CheckerPriority.TEMPORARY_ALLOW,
                BlockReasonCode.STIR_SHAKEN_TRUSTED to CheckerPriority.STIR_SHAKEN_TRUSTED,
                BlockReasonCode.REGULATORY_ALLOW to CheckerPriority.REGULATORY_ALLOW,
                BlockReasonCode.RECENTLY_DIALED to CheckerPriority.RECENTLY_DIALED,
                BlockReasonCode.EMERGENCY_CALLBACK to CheckerPriority.EMERGENCY_CALLBACK,
                BlockReasonCode.ANSWERED_CALLER to CheckerPriority.ANSWERED_CALLER,
                BlockReasonCode.REPEATED_URGENT to CheckerPriority.REPEATED_URGENT,
                BlockReasonCode.CALLER_NAME_TRUST to CheckerPriority.CALLER_NAME_TRUST,
                BlockReasonCode.PUSH_ALERT to CheckerPriority.PUSH_ALERT_BRIDGE,
            )
        allows.forEach { (code, priority) ->
            val text = BlockReasoning.explain(context, reasonCode = code, description = "", confidence = 0).bullets.joinToString(" ")
            assertEquals("$code vs database", priority < CheckerPriority.GITHUB_DATABASE, text.contains("spam database still win"))
            assertEquals(
                "$code vs prefix expansion",
                priority < CheckerPriority.DB_PREFIX_EXPANSION,
                text.contains("database prefix expansion"),
            )
            assertEquals("$code vs prefix list", priority < CheckerPriority.PREFIX_MATCH, text.contains("downloaded prefix list"))
            assertEquals("$code vs contacts-only", priority < CheckerPriority.CONTACTS_ONLY, text.contains("Contacts-only mode"))
            assertEquals(
                "$code vs Android block list",
                priority < CheckerPriority.SYSTEM_BLOCK_LIST,
                text.contains("numbers you blocked in Android"),
            )
            // Region rules, quiet hours and caller-name blocks sit below every allow here.
            assertFalse("$code claims all your rules win", text.contains("own block rules"))
        }
    }

    @Test
    fun `sms context trust says the sender checks and keyword rules come first`() {
        // It runs in the SMS extensions, after the whole number chain found nothing,
        // and below SMS keyword rules (5400).
        val text = BlockReasoning.explain(context, "sms_context", "", 0).bullets.joinToString(" ")
        assertTrue(text.contains("passed every check on the sender's number"))
        assertTrue(text.contains("SMS keyword rules still win"))
    }

    @Test
    fun `meeting mode explanation says the call was silenced, not judged`() {
        val r = BlockReasoning.explain(context, "meeting_mode", "Zoom", 100)
        assertTrue(r.headline.contains("meeting"))
        assertTrue(r.bullets.any { it.contains("Zoom") })
        assertTrue(r.bullets.any { it.contains("wasn't marked as spam") })
    }

    @Test
    fun `heuristic explanation expands comma-separated reasons into bullets`() {
        val r = BlockReasoning.explain(context, "heuristic", "high_spam_npa, voip_spam_range, neighbor_spoof", 78)
        assertTrue(r.headline.contains("78%"))
        assertTrue(r.bullets.any { it.contains("high spam npa") })
        assertTrue(r.bullets.any { it.contains("voip spam range") })
        assertTrue(r.bullets.any { it.contains("neighbor spoof") })
    }

    @Test
    fun `a signal list in Chinese still becomes one bullet per signal`() {
        val enumerationComma = BlockReasoning.explain(context, "heuristic", "可能是邻近伪装、重复来电模式", 78)
        val fullWidthComma = BlockReasoning.explain(context, "sms_content", "风险链接模式，检测到垃圾信息用语", 60)

        assertEquals(listOf("• 可能是邻近伪装", "• 重复来电模式"), enumerationComma.bullets.filter { it.startsWith("• ") })
        assertEquals(listOf("• 风险链接模式", "• 检测到垃圾信息用语"), fullWidthComma.bullets.filter { it.startsWith("• ") })
    }

    @Test
    fun `campaign_burst explanation mentions NPA-NXX burst threshold`() {
        val r = BlockReasoning.explain(context, "campaign_burst", "", 75)
        assertTrue(r.headline.contains("active spam campaign"))
        assertTrue(r.bullets.any { it.contains("5+ distinct numbers") })
    }

    @Test
    fun `campaign_burst explanation includes measured evidence`() {
        val r =
            BlockReasoning.explain(
                context,
                "campaign_burst",
                "Active campaign: 5 neighbor numbers in 6 calls; 1 repeated number(s) suggest callback reuse",
                75,
            )

        assertTrue(r.bullets.any { it.contains("callback reuse") })
    }

    @Test
    fun `ml_scorer explanation reassures the user that inference is on-device`() {
        val r = BlockReasoning.explain(context, "ml_scorer", "", 84)
        assertTrue(r.headline.contains("84%"))
        assertTrue(r.bullets.any { it.contains("on your device") })
    }

    @Test
    fun `emergency_contact explanation is an allow-through headline, not a block`() {
        val r = BlockReasoning.explain(context, "emergency_contact", "", 0)
        assertTrue(r.headline.contains("emergency"))
        assertTrue(r.bullets.any { it.contains("bypasses") })
    }

    @Test
    fun `rcs prefix explanation strips the rcs underscore`() {
        val r = BlockReasoning.explain(context, "rcs_database", "", 100)
        assertTrue(r.headline.contains("RCS"))
        assertTrue(r.bullets.any { it.contains("database") })
    }

    @Test
    fun `stir passed explanation avoids safe caller framing`() {
        val r = BlockReasoning.explain(context, "stir_shaken_trusted", "", 0)
        val text = "${r.headline} ${r.bullets.joinToString(" ")}".lowercase()

        assertTrue(r.headline.contains("authentication passed"))
        assertTrue(text.contains("not a verdict"))
        // The trusted allow also yields to a database entry with recent reports.
        assertTrue(text.contains("spam database entry with recent reports"))
        assertTrue(!text.contains("safe"))
        assertTrue(!text.contains("trusted"))
    }

    @Test
    fun `stir failed explanation uses authentication failure language`() {
        val r = BlockReasoning.explain(context, "stir_shaken_failed", "", 100)

        assertTrue(r.headline.contains("authentication failed"))
        assertTrue(r.bullets.any { it.contains("could not authenticate") })
        assertTrue(r.bullets.any { it.contains("spoofed") })
    }

    @Test
    fun `unknown match reason falls back to a safe default`() {
        val r = BlockReasoning.explain(context, "something_new", "ad-hoc desc", 42)
        assertTrue(r.headline.contains("something_new"))
        assertTrue(r.bullets.any { it.contains("ad-hoc desc") })
        assertTrue(r.bullets.any { it.contains("42%") })
    }

    @Test
    fun `blank match reason reads as allowed, not blocked`() {
        val r = BlockReasoning.explain(context, "", "", 0)
        assertTrue(r.headline.contains("allowed"))
        assertEquals(1, r.bullets.size)
    }

    @Test
    fun `category policy explains the selected action and precedence`() {
        val result =
            BlockReasoning.explain(
                context,
                "category_policy:scam:silence:database",
                "Reported fraud",
                100,
            )

        assertEquals("Scam calls are sent silently to voicemail by your category rule.", result.headline)
        assertTrue(result.bullets.any { "Underlying detection" in it })
        assertTrue(result.bullets.any { "whitelists" in it && "block rules" in it })
    }

    @Test
    fun `a regulatory range is named in the panel's language whatever language the block was written in`() {
        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val english = context.getString(R.string.reg_prefix_brazil_0303_match)
        val zh = chinese.getString(R.string.reg_prefix_brazil_0303_match)
        assertNotEquals(english, zh)

        fun rangeBullets(
            panel: Context,
            description: String,
        ) = BlockReasoning
            .explain(panel, reasonCode = BlockReasonCode.REGULATORY_PREFIX, description = description, confidence = 100)
            .bullets

        // The checker named the range in the system language, or the row is from before a switch.
        val inEnglish = rangeBullets(context, zh)
        assertTrue(inEnglish.toString(), inEnglish.any { english in it } && inEnglish.none { zh in it })
        val inChinese = rangeBullets(chinese, english)
        assertTrue(inChinese.toString(), inChinese.any { zh in it } && inChinese.none { english in it })
        // Text that names no range CallShield knows is shown as written.
        assertTrue(rangeBullets(context, "Range 555").any { "Range 555" in it })
    }
}
