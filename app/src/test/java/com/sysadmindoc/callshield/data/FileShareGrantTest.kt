package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.service.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Android 18 stops copying an ACTION_SEND's EXTRA_STREAM into ClipData, and the
 * receiving app's read grant rides on ClipData. Every file share has to put its
 * file there itself, on the share and on the chooser around it, or the app
 * picked from the share sheet gets a URI it can't open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileShareGrantTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val realUriForFile = FileShareIntent.uriForFile

    @Before
    fun setUp() {
        // The shares hop back to Dispatchers.Main to launch; under runBlocking on
        // Robolectric's main thread that post would never run.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // FileProvider finds a file's root by `rootPath + '/'`, so on a Windows
        // host every file is outside every root. Build the same content URI
        // (authority, root name, file name) the way file_paths.xml maps it.
        if (File.separatorChar == '\\') {
            FileShareIntent.uriForFile = { context, file ->
                Uri
                    .Builder()
                    .scheme("content")
                    .authority("${context.packageName}.fileprovider")
                    .appendPath(file.parentFile!!.name)
                    .appendPath(file.name)
                    .build()
            }
        }
    }

    @After
    fun tearDown() {
        FileShareIntent.uriForFile = realUriForFile
        Dispatchers.resetMain()
    }

    @Test
    fun `backup share grants the backup file`() {
        runBlocking { BackupRestore.shareBackupJson(app, """{"version":1}""", passphrase = null) }
        assertGrantsItsFile(launchedChooser(), "application/json")
    }

    @Test
    fun `blocklist export grants the export file`() {
        val numbers = listOf(SpamNumber(number = "+12025550143", type = "robocall"))
        runBlocking { BlocklistExporter.exportAndShare(app, numbers) }
        assertGrantsItsFile(launchedChooser(), "application/json")
    }

    @Test
    fun `log export grants the csv file`() {
        val calls = listOf(BlockedCall(number = "+12025550143", timestamp = 1_794_000_000_000L))
        runBlocking { LogExporter.exportAsCsv(app, calls) }
        assertGrantsItsFile(launchedChooser(), "text/csv")
    }

    @Test
    fun `crash log share grants the crash file`() {
        File(app.filesDir, "crashes").apply { mkdirs() }.resolve("crash_1794000000000.txt").writeText("boom")
        val share = CrashReporter.shareLatestCrashIntent(app)
        assertNotNull("no crash share was built", share)
        // More's Share crash log row wraps it the same way.
        assertGrantsItsFile(FileShareIntent.chooser(share!!, null), "text/plain")
    }

    private fun launchedChooser(): Intent {
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the share sheet never opened", started)
        return started
    }

    private fun assertGrantsItsFile(
        chooser: Intent,
        mimeType: String,
    ) {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val share = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("the chooser has no share inside it", share)
        val file = IntentCompat.getParcelableExtra(share!!, Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull("the share has no EXTRA_STREAM", file)
        assertEquals("content", file!!.scheme)
        for ((name, intent) in listOf("share" to share, "chooser" to chooser)) {
            val clip = intent.clipData
            assertNotNull("the $name has no ClipData, so Android 18 grants nothing", clip)
            assertEquals(1, clip!!.itemCount)
            assertEquals("the $name grants a different URI than it shares", file, clip.getItemAt(0).uri)
            assertTrue("the $name's ClipData isn't typed $mimeType", clip.description.hasMimeType(mimeType))
            assertTrue("the $name has no read grant", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }
}
