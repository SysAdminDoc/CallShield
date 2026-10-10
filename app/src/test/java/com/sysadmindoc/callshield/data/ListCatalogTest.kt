package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.ListNumberPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ListCatalogTest {
    private val colombia = ListNumberPlan(country = "CO", callingCode = "57", trunkPrefix = "0", nationalLengths = listOf(10))
    private val chile = ListNumberPlan(country = "CL", callingCode = "56", nationalLengths = listOf(9))
    private val turkey = ListNumberPlan(country = "TR", callingCode = "90", trunkPrefix = "0", nationalLengths = listOf(10))

    @Test
    fun `OpenCallShield's national rows become Colombian numbers`() {
        // Each form appears in the list for the same 339 range.
        assertEquals("+573131918305", colombia.toInternational("3131918305"))
        assertEquals("+573395051735", colombia.toInternational("03395051735"))
        assertEquals("+573390714583", colombia.toInternational("00573390714583"))
        assertEquals("+573390714583", colombia.toInternational("573390714583"))
        assertEquals("+573390714583", colombia.toInternational("(339) 071-4583"))
        // A stray dial digit in front of 57, with and without the trunk 0.
        assertEquals("+573395027772", colombia.toInternational("1573395027772"))
        assertEquals("+573230757718", colombia.toInternational("03573230757718"))
    }

    @Test
    fun `a row that isn't one of the country's forms is left to the phone`() {
        assertNull("already international", colombia.toInternational("+12025550143"))
        assertNull("a digit too many after the trunk prefix", colombia.toInternational("033925590578"))
        assertNull("a digit too many", colombia.toInternational("31183512482"))
        assertNull(colombia.toInternational(""))
    }

    @Test
    fun `the length tells a calling code from a national number that starts the same way`() {
        assertEquals("+56561234567", chile.toInternational("561234567"))
        assertEquals("+56912345678", chile.toInternational("56912345678"))
        assertEquals("+56412345678", chile.toInternational("412345678"))
        assertEquals("+905321234567", turkey.toInternational("05321234567"))
        assertEquals("+905321234567", turkey.toInternational("905321234567"))
    }

    @Test
    fun `the published catalog offers four lists, all off, each with a license and a number plan`() {
        val catalog = ListCatalog.parse(File("../data/list_catalog.json").readText())

        assertEquals(listOf("opencallshield-co", "spamchile-cl", "turkish-spam-numbers-tr", "listahu-py"), catalog.entries.map { it.id })
        assertEquals(listOf("MIT", "GPL-2.0", "GPL-3.0", "CC BY-NC-SA 4.0"), catalog.entries.map { it.license })
        assertEquals(listOf("CO", "CL", "TR", "PY"), catalog.entries.map { it.numberPlan.country })
        assertEquals(colombia, catalog.entries.first().numberPlan)
        assertTrue(catalog.entries.all { it.url.startsWith("https://") && it.licenseUrl.startsWith("https://") })
    }

    @Test
    fun `an entry the app can't use is left out and the rest still show`() {
        val catalog =
            ListCatalog.parse(
                catalogJson(
                    entry("good"),
                    entry("on-by-default", enabledByDefault = true),
                    entry("plain-http", url = "http://lists.example.test/plain.txt"),
                    entry("xml", format = "xml"),
                    entry("no-license", license = ""),
                    entry("bad-country", country = "Colombia"),
                    entry("good", url = "https://lists.example.test/same-id.txt"),
                    entry("same-url", url = "https://lists.example.test/good.txt"),
                ),
            )

        assertEquals(listOf("good"), catalog.entries.map { it.id })
        assertEquals(3, catalog.revision)
    }

    @Test
    fun `a catalog with no usable list, no revision or another schema is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ListCatalog.parse(catalogJson(entry("x", format = "xml"))) }
        assertThrows(IllegalArgumentException::class.java) { ListCatalog.parse(catalogJson(entry("x"), revision = 0)) }
        assertThrows(IllegalArgumentException::class.java) { ListCatalog.parse(catalogJson(entry("x"), version = 2)) }
        assertThrows(IllegalArgumentException::class.java) { ListCatalog.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) { ListCatalog.parse("""{"version": 1, "revision": 1, "lists": "none"}""") }
    }

    private fun catalogJson(
        vararg entries: String,
        version: Int = 1,
        revision: Int = 3,
    ) = """{"version": $version, "revision": $revision, "lists": [${entries.joinToString(",")}]}"""

    private fun entry(
        id: String,
        url: String = "https://lists.example.test/$id.txt",
        format: String = "txt",
        license: String = "MIT",
        country: String = "CO",
        enabledByDefault: Boolean = false,
    ) = """
        {"id": "$id", "name": "List $id", "country": "$country", "calling_code": "57", "trunk_prefix": "0",
         "national_lengths": [10], "url": "$url", "homepage": "https://lists.example.test/",
         "license": "$license", "license_url": "https://lists.example.test/LICENSE", "format": "$format",
         "enabled_by_default": $enabledByDefault}
        """
}
