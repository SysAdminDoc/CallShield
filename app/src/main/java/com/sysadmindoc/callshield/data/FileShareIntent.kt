package com.sysadmindoc.callshield.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share intents for one file behind the app's FileProvider.
 *
 * The receiving app's read grant rides on the intent's ClipData. Through
 * Android 17 the system copied an ACTION_SEND's EXTRA_STREAM into ClipData
 * when the intent launched, and Android 18 stops doing that, so a share that
 * set only EXTRA_STREAM reached the other app with no access to the file.
 * These set ClipData themselves, the way the system used to (the intent's own
 * MIME type, one item), on the target and on the chooser.
 */
object FileShareIntent {
    private const val GRANT_AND_TASK = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

    /**
     * Maps a file under one of `res/xml/file_paths.xml`'s roots to its content URI.
     * Tests on a Windows host swap it: FileProvider matches a root by
     * `rootPath + '/'`, which a `C:\` path never contains.
     */
    @VisibleForTesting
    internal var uriForFile: (Context, File) -> Uri = { context, file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun forFile(
        context: Context,
        file: File,
        mimeType: String,
        subject: String,
    ): Intent = send(uriForFile(context, file), mimeType, subject)

    fun send(
        uri: Uri,
        mimeType: String,
        subject: String,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData(ClipDescription(null, arrayOf(mimeType)), ClipData.Item(uri))
            addFlags(GRANT_AND_TASK)
        }

    fun chooser(
        target: Intent,
        title: CharSequence?,
    ): Intent =
        Intent.createChooser(target, title).apply {
            clipData = target.clipData
            addFlags(GRANT_AND_TASK)
        }
}
