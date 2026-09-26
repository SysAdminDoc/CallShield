package com.sysadmindoc.callshield.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English UI text uses sentences, commas and colons, never an em or en dash.
 * The in-app release history carried 36 of them and the weekday schedule
 * label read "Mon–Fri". Chinese keeps its own punctuation, so only the base
 * resources and the English-only release history are checked.
 */
class EnglishTextDashTest {
    private val dashes = Regex("[–—]")

    @Test
    fun `base string resources have no em or en dash`() {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/values/strings.xml"))
        val offenders =
            listOf("string", "item")
                .flatMap { tag ->
                    val nodes = document.getElementsByTagName(tag)
                    (0 until nodes.length).map { index -> nodes.item(index) as Element }
                }.filter { element -> dashes.containsMatchIn(element.textContent) }
                .map { element -> element.getAttribute("name").ifEmpty { element.textContent.take(60) } }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `no English string says a text was blocked`() {
        // A flagged text still reaches the messaging app; only calls are blocked.
        // Several strings said otherwise, and the Home counter read "Texts blocked".
        val blockedText =
            Regex(
                listOf(
                    // "texts were blocked", "messages have been blocked"
                    """\b(?:texts?|sms|messages?)\s+(?:(?:was|were|is|are|been|got|(?:have|has|had)\s+been)\s+)?blocked\b""",
                    // "blocked SMS", "blocked-message review", "blocked-call/SMS alerts"
                    """\bblocked[\s-]+(?:calls?\s*/\s*)?(?:sms|texts?|messages?)\b""",
                    // "Blocked calls and texts", "blocked call and SMS log", "Blocked calls & SMS"
                    """\bblocked\s+calls?\s*(?:and|or|&amp;|&|/)\s*(?:sms|texts?|messages?)\b""",
                    // "No calls or messages have been blocked", "messages ... are never blocked"
                    """\b(?:texts?|sms|messages?)\b[^.]*\bblocked\b""",
                    // "This item was blocked because the message contained a website"
                    """\bblocked\s+because\b[^.]*\b(?:messages?|senders?|texts?)\b""",
                ).joinToString("|"),
                RegexOption.IGNORE_CASE,
            )
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/values/strings.xml"))
        val offenders =
            listOf("string", "item")
                .flatMap { tag ->
                    val nodes = document.getElementsByTagName(tag)
                    (0 until nodes.length).map { index -> nodes.item(index) as Element }
                }.filter { element -> blockedText.containsMatchIn(element.textContent) }
                .map { element -> element.getAttribute("name").ifEmpty { element.textContent.take(60) } }

        assertEquals(emptyList<String>(), offenders)
        listOf(
            "Texts blocked",
            "Raw blocked SMS text",
            "spam calls/texts are blocked",
            "Blocked calls and texts will appear here.",
            "Blocked calls & SMS",
            "No calls or messages have been blocked.",
            "Blocked-call/SMS alerts",
            "This item was blocked because the sender sent several messages in a short time.",
        ).forEach {
            assertEquals(it, true, blockedText.containsMatchIn(it))
        }
    }

    @Test
    fun `release history text has no em or en dash`() {
        val source = File("src/main/java/com/sysadmindoc/callshield/ui/screens/more/ChangelogScreen.kt").readText()
        val offenders =
            Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
                .findAll(source)
                .map { literal -> literal.value }
                .filter { literal -> dashes.containsMatchIn(literal) }
                .toList()

        assertEquals(emptyList<String>(), offenders)
    }
}
