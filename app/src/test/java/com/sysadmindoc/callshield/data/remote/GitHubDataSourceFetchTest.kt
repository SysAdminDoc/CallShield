package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real download path with GitHub played by an interceptor, so the
 * signature check is proven where feeds are fetched and not only in its
 * helper. Nothing here touches the network.
 */
class GitHubDataSourceFetchTest {
    private val hotList = File("..", GitHubDataSource.HOT_LIST_PATH).readBytes()
    private val hotListSignature = File("..", GitHubDataSource.HOT_LIST_PATH + ".sig").readBytes()
    private val requested = mutableListOf<String>()

    @Test
    fun `a signed download is accepted, and its signature is fetched from the same branch`() {
        val result = fetchHotList(mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature))

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertTrue(requested.contains("$MASTER$HOT_LIST.sig"))
    }

    @Test
    fun `a download with no signature is refused as unsigned, not reported as a missing file`() {
        // The fallback branch answers 404; that must not hide why master's copy was refused.
        val result = fetchHotList(mapOf(HOT_LIST to hotList))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
    }

    @Test
    fun `a download changed in transit is refused`() {
        val tampered = String(hotList, Charsets.UTF_8).replaceFirst("\"count\"", "\"Count\"").toByteArray(Charsets.UTF_8)

        val result = fetchHotList(mapOf(HOT_LIST to tampered, "$HOT_LIST.sig" to hotListSignature))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
    }

    private fun fetchHotList(masterFiles: Map<String, ByteArray>) =
        runBlocking {
            GitHubDataSource(
                Interceptor { chain ->
                    val url = chain.request().url.toString()
                    requested += url
                    val body =
                        when {
                            url == REPOSITORY_API -> "{\"default_branch\":\"master\"}".toByteArray()
                            url.startsWith(MASTER) -> masterFiles[url.removePrefix(MASTER)]
                            else -> null
                        }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (body == null) 404 else 200)
                        .message(if (body == null) "Not Found" else "OK")
                        .body((body ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).fetchHotListSnapshot(GitHubDataSource.DEFAULT_REPO_OWNER, GitHubDataSource.DEFAULT_REPO_NAME)
        }

    private companion object {
        const val HOT_LIST = GitHubDataSource.HOT_LIST_PATH
        const val REPOSITORY_API = "https://api.github.com/repos/SysAdminDoc/CallShield"
        const val MASTER = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/"
    }
}
