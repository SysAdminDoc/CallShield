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
