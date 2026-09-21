package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The real download path with GitHub, and the optional mirror, played by an
 * interceptor, so the signature check is proven where feeds are fetched and
 * not only in its helper. Nothing here touches the network.
 */
class GitHubDataSourceFetchTest {
    private val hotList = file(HOT_LIST)
    private val hotListSignature = file("$HOT_LIST.sig")
    private val requested = mutableListOf<String>()

    @After
    fun clearMirror() = FeedMirror.set(null)

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
        val result = fetchHotList(mapOf(HOT_LIST to tampered(hotList), "$HOT_LIST.sig" to hotListSignature))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
    }

    @Test
    fun `with no mirror set, only GitHub is asked`() {
        fetchHotList(masterFiles = emptyMap(), mirrorFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature))

        assertTrue(requested.toString(), requested.none { it.startsWith(MIRROR) })
    }

    @Test
    fun `the mirror isn't asked while GitHub is serving`() {
        FeedMirror.set(MIRROR)

        val result =
            fetchHotList(
                masterFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature),
                mirrorFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature),
            )

        assertTrue(result.isSuccess)
        assertTrue(requested.toString(), requested.none { it.startsWith(MIRROR) })
    }

    @Test
    fun `once every GitHub branch fails, the mirror serves the feed under its own signature`() {
        FeedMirror.set(MIRROR)

        val result = fetchHotList(masterFiles = emptyMap(), mirrorFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature))

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val firstMirrorRequest = requested.indexOfFirst { it.startsWith(MIRROR) }
        assertTrue(requested.toString(), requested.take(firstMirrorRequest).any { it == "$MASTER$HOT_LIST" })
        assertTrue(requested.toString(), requested.contains("$MIRROR$HOT_LIST.sig"))
    }

    @Test
    fun `the database and model downloads fall through to the mirror too`() {
        FeedMirror.set(MIRROR)
        val mirrorFiles = listOf(MANIFEST, MODEL).flatMap { path -> listOf(path to file(path), "$path.sig" to file("$path.sig")) }.toMap()
        val source = dataSource(emptyMap(), mirrorFiles)

        val manifest = runBlocking { source.fetchSpamShardManifest(OWNER, REPO) }
        val model = runBlocking { source.fetchModelWeightsJson() }

        assertTrue(manifest.exceptionOrNull()?.toString(), manifest.isSuccess)
        // The mirror hands back GitHub's exact bytes, so everything downstream,
        // including the manifest rollback checks, sees what GitHub would have served.
        assertArrayEquals(file(MODEL), model.getOrThrow().toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `a mirror can't serve a feed the project didn't sign`() {
        FeedMirror.set(MIRROR)

        val result = fetchHotList(masterFiles = emptyMap(), mirrorFiles = mapOf(HOT_LIST to tampered(hotList), "$HOT_LIST.sig" to hotListSignature))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
    }

    @Test
    fun `a mirror's copy can't borrow GitHub's signature`() {
        FeedMirror.set(MIRROR)

        // GitHub has only the signature, the mirror has only the body.
        val result = fetchHotList(masterFiles = mapOf("$HOT_LIST.sig" to hotListSignature), mirrorFiles = mapOf(HOT_LIST to hotList))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
        assertTrue(requested.toString(), requested.contains("$MIRROR$HOT_LIST.sig"))
    }

    @Test
    fun `a mirror that answers 404 doesn't hide GitHub's own failure`() {
        FeedMirror.set(MIRROR)

        val result = fetchHotList(masterFiles = emptyMap(), mirrorFiles = emptyMap(), gitHubFailure = SSLPeerUnverifiedException("pin mismatch"))

        // A certificate failure has to reach the update notice, and a 404 would
        // also have told SyncRepository not to retry.
        assertTrue(result.exceptionOrNull().toString(), HttpClient.isCertificateTrustFailure(result.exceptionOrNull()))
    }

    private fun fetchHotList(
        masterFiles: Map<String, ByteArray>,
        mirrorFiles: Map<String, ByteArray> = emptyMap(),
        gitHubFailure: IOException? = null,
    ) = runBlocking { dataSource(masterFiles, mirrorFiles, gitHubFailure).fetchHotListSnapshot(OWNER, REPO) }

    private fun dataSource(
        masterFiles: Map<String, ByteArray>,
        mirrorFiles: Map<String, ByteArray>,
        gitHubFailure: IOException? = null,
    ) = GitHubDataSource(
        Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            if (gitHubFailure != null && url.startsWith(RAW)) throw gitHubFailure
            val body =
                when {
                    url == REPOSITORY_API -> "{\"default_branch\":\"master\"}".toByteArray()
                    url.startsWith(MASTER) -> masterFiles[url.removePrefix(MASTER)]
                    url.startsWith(MIRROR) -> mirrorFiles[url.removePrefix(MIRROR)]
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
    )

    private fun tampered(bytes: ByteArray) = String(bytes, Charsets.UTF_8).replaceFirst("\"count\"", "\"Count\"").toByteArray(Charsets.UTF_8)

    private fun file(path: String) = File("..", path).readBytes()

    private companion object {
        const val HOT_LIST = GitHubDataSource.HOT_LIST_PATH
        const val MANIFEST = GitHubDataSource.SHARD_MANIFEST_PATH
        const val MODEL = GitHubDataSource.MODEL_WEIGHTS_PATH
        const val OWNER = GitHubDataSource.DEFAULT_REPO_OWNER
        const val REPO = GitHubDataSource.DEFAULT_REPO_NAME
        const val REPOSITORY_API = "https://api.github.com/repos/SysAdminDoc/CallShield"
        const val RAW = "https://raw.githubusercontent.com/"
        const val MASTER = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/"
        const val MIRROR = "https://mirror.example.test/callshield/"
    }
}
