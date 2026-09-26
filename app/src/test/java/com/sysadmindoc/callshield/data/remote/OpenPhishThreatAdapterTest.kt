package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPhishThreatAdapterTest {
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
            val adapter = OpenPhishThreatAdapter(client)
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
