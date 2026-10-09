package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Moshi reads GitHubDataSource's private payload classes by reflection, and
 * the adapters are built when the class loads. A payload R8 is free to strip
 * crashes a release build at launch ("Cannot serialize abstract class"),
 * while every debug build and unit test passes. So each private data class
 * needs a keep rule unless it never meets Moshi.
 */
class GitHubDataSourceKeepRulesTest {
    private val source = File("src/main/java/com/sysadmindoc/callshield/data/remote/GitHubDataSource.kt").readText()
    private val rules = File("proguard-rules.pro").readText()

    @Test
    fun `every private payload class Moshi reads has a keep rule`() {
        val classes =
            Regex("""private data class (\w+)\(""")
                .findAll(source)
                .map { it.groupValues[1] }
                .filterNot { it in NOT_JSON }
                .toList()
        val missing =
            classes.filterNot {
                "-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource\$$it { *; }" in rules
            }

        assertEquals(emptyList<String>(), missing)
    }

    private companion object {
        /** Built in code from constants, never parsed from JSON. */
        val NOT_JSON = setOf("RawFeedSpec", "FeedSource", "GitHubPin")
    }
}
