package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.net.SocketTimeoutException
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
    private var now = 1_000_000L

    @After
    fun clearMirror() = FeedMirror.resetForTest()

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
        masterStatus: Map<String, Int> = emptyMap(),
        commitFiles: Map<String, ByteArray>? = null,
    ) = runBlocking {
        dataSource(masterFiles, mirrorFiles, gitHubFailure, masterStatus, commitFiles).fetchHotListSnapshot(OWNER, REPO)
    }

    /**
     * [masterStatus] makes master answer an error for a path. [commitFiles] is
     * what raw serves at the commit master points to; without it the commit
     * API answers 404. [apiFailure] is thrown for every api.github.com call.
     * Like the real CDN, the fake ignores query strings.
     */
    private fun dataSource(
        masterFiles: Map<String, ByteArray>,
        mirrorFiles: Map<String, ByteArray>,
        gitHubFailure: IOException? = null,
        masterStatus: Map<String, Int> = emptyMap(),
        commitFiles: Map<String, ByteArray>? = null,
        apiFailure: IOException? = null,
    ) = GitHubDataSource(
        Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            if (gitHubFailure != null && url.startsWith(RAW)) throw gitHubFailure
            if (apiFailure != null && url.startsWith(API)) throw apiFailure
            val file = url.substringBefore("?")
            val status = if (file.startsWith(MASTER)) masterStatus[file.removePrefix(MASTER)] else null
            val body =
                when {
                    status != null -> null
                    file == REPOSITORY_API -> "{\"default_branch\":\"master\"}".toByteArray()
                    file == COMMIT_API -> commitFiles?.let { COMMIT.toByteArray() }
                    file.startsWith(MASTER) -> masterFiles[file.removePrefix(MASTER)]
                    file.startsWith(PINNED) -> commitFiles?.get(file.removePrefix(PINNED))
                    file.startsWith(MIRROR) -> mirrorFiles[file.removePrefix(MIRROR)]
                    else -> null
                }
            val code = status ?: if (body == null) 404 else 200
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Status $code")
                .body((body ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
                .build()
        },
        clock = { now },
    )

    @Test
    fun `a signature the host fails to serve is a network failure, not an unsigned feed`() {
        val result = fetchHotList(masterFiles = mapOf(HOT_LIST to hotList), masterStatus = mapOf("$HOT_LIST.sig" to 503))

        val error = result.exceptionOrNull()
        assertTrue(error.toString(), error is GitHubFeedHttpException && error.code == 503)
    }

    @Test
    fun `a fallback branch that doesn't exist doesn't hide why the real one failed`() {
        // A 404 would also have told SyncRepository not to retry a transient failure.
        val result = fetchHotList(masterFiles = emptyMap(), masterStatus = mapOf(HOT_LIST to 503))

        val error = result.exceptionOrNull()
        assertTrue(error.toString(), error is GitHubFeedHttpException && error.code == 503)
    }

    @Test
    fun `a body the cache paired with an old signature is fetched again from its commit`() {
        val oldSignature = file("$MANIFEST.sig")

        val result =
            fetchHotList(
                masterFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to oldSignature),
                commitFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature),
            )

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertTrue(requested.toString(), requested.contains(COMMIT_API))
        assertTrue(requested.toString(), requested.contains("$PINNED$HOT_LIST.sig"))
    }

    @Test
    fun `a signature that doesn't verify at the commit either stays refused`() {
        val oldSignature = file("$MANIFEST.sig")
        val files = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to oldSignature)

        val result = fetchHotList(masterFiles = files, commitFiles = files)

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
    }

    @Test
    fun `a refusal stands when the commit can't be resolved`() {
        val oldSignature = file("$MANIFEST.sig")

        val result = fetchHotList(masterFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to oldSignature))

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
        assertTrue(requested.toString(), requested.contains(COMMIT_API))
    }

    @Test
    fun `a refused mirror copy isn't retried at a GitHub commit`() {
        FeedMirror.set(MIRROR)
        val oldSignature = file("$MANIFEST.sig")

        val result =
            fetchHotList(
                masterFiles = emptyMap(),
                mirrorFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to oldSignature),
                commitFiles = mapOf(HOT_LIST to hotList, "$HOT_LIST.sig" to hotListSignature),
            )

        assertEquals(GitHubFeedFailureReason.SIGNATURE, (result.exceptionOrNull() as GitHubFeedValidationException).reason)
        assertTrue(requested.toString(), requested.none { it.startsWith(PINNED) })
    }

    @Test
    fun `once GitHub can't be reached, the next feeds go to the mirror first`() {
        FeedMirror.set(MIRROR)
        val source = dataSource(emptyMap(), signed(HOT_LIST, MODEL), gitHubFailure = SocketTimeoutException("connect timed out"))

        assertTrue(runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }.isSuccess)
        requested.clear()
        val model = runBlocking { source.fetchModelWeightsJson() }

        assertTrue(model.exceptionOrNull()?.toString(), model.isSuccess)
        assertTrue(requested.toString(), requested.first().startsWith(MIRROR))
        assertTrue(requested.toString(), requested.none { it.startsWith(RAW) || it.startsWith(API) })
    }

    @Test
    fun `GitHub goes first again once the outage window has passed`() {
        FeedMirror.set(MIRROR)
        val source = dataSource(emptyMap(), signed(HOT_LIST), gitHubFailure = SocketTimeoutException("connect timed out"))
        runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }

        now += GitHubDataSource.GITHUB_OUTAGE_MS + 1
        requested.clear()
        runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }

        assertTrue(requested.toString(), !requested.first().startsWith(MIRROR))
    }

    @Test
    fun `an answer from GitHub, even a 404, doesn't count as unreachable`() {
        FeedMirror.set(MIRROR)
        val source = dataSource(emptyMap(), signed(HOT_LIST))
        runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }

        requested.clear()
        runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }

        assertTrue(requested.toString(), !requested.first().startsWith(MIRROR))
    }

    @Test
    fun `a failed default-branch lookup isn't repeated for every feed`() {
        val source = dataSource(signed(HOT_LIST, MODEL), emptyMap(), apiFailure = SocketTimeoutException("timeout"))

        val hot = runBlocking { source.fetchHotListSnapshot(OWNER, REPO) }
        val model = runBlocking { source.fetchModelWeightsJson() }

        assertTrue(hot.exceptionOrNull()?.toString(), hot.isSuccess)
        assertTrue(model.exceptionOrNull()?.toString(), model.isSuccess)
        assertEquals(requested.toString(), 1, requested.count { it == REPOSITORY_API })
    }

    @Test
    fun `a fetch that starts before the mirror setting is read waits for it`() {
        FeedMirror.startLoading()
        val source = dataSource(emptyMap(), signed(HOT_LIST))

        val result =
            runBlocking {
                val fetch = async(Dispatchers.IO) { source.fetchHotListSnapshot(OWNER, REPO) }
                delay(200)
                FeedMirror.set(MIRROR)
                fetch.await()
            }

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
    }

    private fun signed(vararg paths: String) = paths.flatMap { path -> listOf(path to file(path), "$path.sig" to file("$path.sig")) }.toMap()

    private fun tampered(bytes: ByteArray) = String(bytes, Charsets.UTF_8).replaceFirst("\"count\"", "\"Count\"").toByteArray(Charsets.UTF_8)

    private fun file(path: String) = File("..", path).readBytes()

    private companion object {
        const val HOT_LIST = GitHubDataSource.HOT_LIST_PATH
        const val MANIFEST = GitHubDataSource.SHARD_MANIFEST_PATH
        const val MODEL = GitHubDataSource.MODEL_WEIGHTS_PATH
        const val OWNER = GitHubDataSource.DEFAULT_REPO_OWNER
        const val REPO = GitHubDataSource.DEFAULT_REPO_NAME
        const val REPOSITORY_API = "https://api.github.com/repos/SysAdminDoc/CallShield"
        const val COMMIT_API = "https://api.github.com/repos/SysAdminDoc/CallShield/commits/master"
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val API = "https://api.github.com/"
        const val RAW = "https://raw.githubusercontent.com/"
        const val MASTER = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/"
        const val PINNED = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/$COMMIT/"
        const val MIRROR = "https://mirror.example.test/callshield/"
    }
}
