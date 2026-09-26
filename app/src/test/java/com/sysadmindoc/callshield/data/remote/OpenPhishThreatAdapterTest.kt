package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPhishThreatAdapterTest {
    // OkHttp reads the public suffix list from an Android asset, which a JVM
    // test can't load, so these tests use a few real entries of it.
    private val suffixes = setOf("test", "com", "amazonaws.com", "s3.us-east-1.amazonaws.com")

    private fun registrable(url: HttpUrl): String? {
        val labels = url.host.split('.')
        val suffixStart = labels.indices.first { labels.drop(it).joinToString(".") in suffixes }
        return if (suffixStart == 0) null else labels.drop(suffixStart - 1).joinToString(".")
    }

    private fun adapterOf(client: OkHttpClient) = OpenPhishThreatAdapter(client, ::registrable)

    private fun feedOf(vararg urls: String): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(urls.joinToString("\n").toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()

    @Test
    fun `a feed entry on a subdomain flags that host and below it, not its siblings`() =
        runBlocking {
            // Links used to reach the adapter as their registrable domain, so a
            // login.example.test entry never matched a link on login.example.test.
            val adapter = adapterOf(feedOf("https://login.example.test/verify", "https://evil.test/"))

            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://login.example.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://eu.login.example.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://shop.example.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://example.test/", 0L).verdict)
            // An entry on a whole domain still covers its subdomains.
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://pay.evil.test/", 0L).verdict)
        }

    @Test
    fun `an entry on a shared host flags its bucket, not the host or every bucket under it`() =
        runBlocking {
            // The live feed of 2026-09-26 listed both regional S3 endpoints, which
            // are public suffixes. Walking up from acme-invoices.s3... reached them
            // and flagged every bucket in the region.
            val adapter =
                adapterOf(
                    feedOf(
                        "https://s3.us-east-1.amazonaws.com/evil-bucket/login.html",
                        "https://www.victim.test/verify",
                    ),
                )

            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://acme-invoices.s3.us-east-1.amazonaws.com/x.pdf", 0L).verdict)
            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://s3.us-east-1.amazonaws.com/acme-invoices/x.pdf", 0L).verdict)
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://s3.us-east-1.amazonaws.com/evil-bucket/other.html", 0L).verdict)
            // A www. entry covers the bare domain but not its siblings.
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://www.victim.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://victim.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://shop.victim.test/", 0L).verdict)
        }

    @Test
    fun `without the suffix list an entry flags only its own host`() =
        runBlocking {
            val adapter = OpenPhishThreatAdapter(feedOf("https://login.example.test/verify")) { error("no suffix list") }

            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://login.example.test/", 0L).verdict)
            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup("https://eu.login.example.test/", 0L).verdict)
        }

    @Test
    fun `a link on a shared host still reaches the on-device feed`() {
        val url = "https://s3.us-east-1.amazonaws.com/evil-bucket/login.html?id=1"

        assertEquals("", UrlSafetyChecker.normalizeRemoteLookupUrl(url, registrableDomain = ::registrable))
        assertEquals("https://s3.us-east-1.amazonaws.com/evil-bucket/login.html", UrlSafetyChecker.normalizeOnDeviceLookupUrl(url))
    }

    @Test
    fun `only the on-device feed asks for the full host`() {
        assertEquals(true, OpenPhishThreatAdapter(feedOf()).matchesOnDevice)
        assertEquals(false, PhishTankThreatAdapter().matchesOnDevice)
    }

    @Test
    fun `feed waits six hours and a 304 renews the existing hosts`() =
        runBlocking {
            val etags = mutableListOf<String?>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        etags += chain.request().header("If-None-Match")
                        val (code, body, etag) =
                            when (etags.size) {
                                1 -> Triple(200, "https://bad.example.test/login\n", "\"feed-v1\"")
                                2 -> Triple(304, "", null)
                                else -> Triple(200, "https://new.example.test/login\n", "\"feed-v2\"")
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message(if (code == 304) "Not Modified" else "OK")
                            .body(body.toResponseBody("text/plain".toMediaType()))
                            .apply { etag?.let { addHeader("ETag", it) } }
                            .build()
                    }.build()
            val adapter = adapterOf(client)
            val bad = "https://bad.example.test/"
            val sixHours = 6L * 60L * 60L * 1_000L

            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup(bad, 0L).verdict)
            val lastCachedResult = adapter.lookup(bad, sixHours - 1L)
            assertEquals(UrlThreatVerdict.MALICIOUS, lastCachedResult.verdict)
            assertEquals(sixHours, lastCachedResult.expiresAtMillis)
            assertEquals(listOf(null), etags)

            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup(bad, sixHours).verdict)
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup(bad, 2 * sixHours - 1L).verdict)
            assertEquals(listOf(null, "\"feed-v1\""), etags)

            assertEquals(UrlThreatVerdict.CLEAN, adapter.lookup(bad, 2 * sixHours).verdict)
            assertEquals(UrlThreatVerdict.MALICIOUS, adapter.lookup("https://new.example.test/", 2 * sixHours).verdict)
            assertEquals(listOf(null, "\"feed-v1\"", "\"feed-v1\""), etags)
        }
}
