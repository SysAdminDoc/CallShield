package com.sysadmindoc.callshield

import com.sysadmindoc.callshield.data.NotificationScreeningSources
import com.sysadmindoc.callshield.data.PushAlertRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * On API 30+ an app can only look up the packages it declares in `<queries>`.
 * The source pickers call getApplicationInfo() for every catalog package, so a
 * package missing from the manifest shows as "Not installed" even when it is.
 */
class ManifestQueriesTest {
    @Test
    fun `manifest queries every catalog package the source pickers look up`() {
        val declared = declaredQueryPackages()
        val expected =
            PushAlertRegistry.ALERT_SOURCE_PACKAGES +
                NotificationScreeningSources.catalog.map { it.packageName }

        assertEquals("catalog packages missing from <queries>", emptySet<String>(), expected - declared)
        assertEquals("<queries> packages no catalog uses", emptySet<String>(), declared - expected)
    }

    @Test
    fun `queries block adds package visibility without a new permission`() {
        val manifest = locate(MANIFEST_PATH).readText()

        assertTrue(declaredQueryPackages().isNotEmpty())
        assertTrue("QUERY_ALL_PACKAGES must not be requested", "QUERY_ALL_PACKAGES" !in manifest)
    }

    private fun declaredQueryPackages(): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(locate(MANIFEST_PATH))
        val queries = document.getElementsByTagName("queries")
        val packages = mutableSetOf<String>()
        for (index in 0 until queries.length) {
            val entries = (queries.item(index) as Element).getElementsByTagName("package")
            for (entry in 0 until entries.length) {
                packages += (entries.item(entry) as Element).getAttributeNS(ANDROID_NAMESPACE, "name")
            }
        }
        return packages
    }

    private fun locate(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $relativePath walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
