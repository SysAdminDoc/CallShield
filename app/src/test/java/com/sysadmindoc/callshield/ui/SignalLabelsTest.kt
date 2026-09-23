package com.sysadmindoc.callshield.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class SignalLabelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Every token the two analyzers can put in a reason list, read from their
     * source so a new signal without a label fails here instead of reaching
     * the block log as "snake case".
     */
    private fun analyzerTokens(): Set<String> {
        val dir = File("src/main/java/com/sysadmindoc/callshield/data")
        val added = Regex("""reasons\.add\("([a-z_]+)"\)""")
        val scored = Regex("""-> \d+ to "([a-z_]+)"""")
        return listOf("SpamHeuristics.kt", "SmsContentAnalyzer.kt")
            .flatMap { name ->
                val text = File(dir, name).readText()
                (added.findAll(text) + scored.findAll(text)).map { it.groupValues[1] }.toList()
            }.toSet()
    }

    private fun english(): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )

    @Test
    fun `every signal the analyzers can raise has a label`() {
        val tokens = analyzerTokens()

        // Positive control: the scan has to find the signals it guards.
        assertTrue("found only $tokens", tokens.size >= 18)
        assertTrue(tokens.containsAll(listOf("neighbor_spoof", "short_msg_with_url", "lookalike_host")))
        for (token in tokens) {
            assertNotNull("no label for $token", signalLabelRes(token))
        }
    }

    @Test
    fun `no label contains a list separator in either language`() {
        // BlockReasoning splits a stored list on these to rebuild the bullets.
        val separators = charArrayOf(',', '，', '、')
        for (language in listOf(context, english())) {
            for (token in analyzerTokens()) {
                val label = signalLabel(language, token)
                assertFalse("\"$label\" ($token) would split", label.any { it in separators })
            }
        }
    }

    @Test
    fun `labels are joined once each with the language's separator`() {
        val joined = listOf("shortened_url", "suspicious_tld", "spam_keywords").map { signalLabel(context, it) }.joinSignalLabels(context)

        assertEquals("风险链接模式、检测到垃圾信息用语", joined)
        assertEquals(
            "Risky link pattern, Spam wording detected",
            listOf("shortened_url", "suspicious_tld", "spam_keywords").map { signalLabel(english(), it) }.joinSignalLabels(english()),
        )
    }

    @Test
    fun `an unknown token still reads as words`() {
        assertEquals("brand new signal", signalLabel(context, "brand_new_signal"))
    }
}
