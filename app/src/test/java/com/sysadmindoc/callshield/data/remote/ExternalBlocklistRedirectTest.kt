package com.sysadmindoc.callshield.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBlocklistRedirectTest {
    private val original = "https://lists.example.test/feed.txt".toHttpUrl()

    @Test
    fun `relative same-host redirect is allowed and normalized`() {
        val redirected =
            validatedExternalBlocklistRedirect(
                originalUrl = original,
                currentUrl = original,
                location = "/v2/feed.txt#ignored",
                redirectCount = 0,
            )

        assertEquals("https://lists.example.test/v2/feed.txt", redirected.toString())
    }

    @Test
    fun `redirect to a different or private host is rejected`() {
        val crossHost =
            runCatching {
                validatedExternalBlocklistRedirect(original, original, "https://cdn.example.test/feed.txt", 0)
            }.exceptionOrNull()
        val privateHost =
            runCatching {
                validatedExternalBlocklistRedirect(original, original, "https://192.168.1.1/feed.txt", 0)
            }.exceptionOrNull()

        assertTrue(crossHost is IllegalArgumentException)
        assertTrue(privateHost is IllegalArgumentException)
    }

    @Test
    fun `redirect target is revalidated and hop count is capped`() {
        val cleartext =
            runCatching {
                validatedExternalBlocklistRedirect(original, original, "http://lists.example.test/feed.txt", 0)
            }.exceptionOrNull()
        val exhausted =
            runCatching {
                validatedExternalBlocklistRedirect(
                    original,
                    original,
                    "/next",
                    EXTERNAL_BLOCKLIST_MAX_REDIRECTS,
                )
            }.exceptionOrNull()

        assertTrue(cleartext is IllegalArgumentException)
        assertTrue(exhausted is IllegalArgumentException)
    }
}
