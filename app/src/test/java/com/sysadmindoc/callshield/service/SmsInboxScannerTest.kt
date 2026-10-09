package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A rescan reads each text the way it was read when it arrived, SIM included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsInboxScannerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each text is checked with the SIM it arrived on`() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.READ_SMS)
        val inbox = Robolectric.setupContentProvider(FakeSmsInbox::class.java, "sms")
        inbox.rows =
            listOf(
                InboxRow("Unverified", "Your appointment is on Tuesday", date = 3L, subscriptionId = 2),
                InboxRow("+15550100001", "See you at 6", date = 2L, subscriptionId = 1),
                InboxRow("+15550100002", "Running late", date = 1L, subscriptionId = -1),
                InboxRow("+15550100003", "Lunch?", date = 0L, subscriptionId = null),
            )
        val asked = mutableListOf<Pair<String, Int?>>()

        val result =
            runBlocking {
                SmsInboxScanner.scan(context, limit = 10) { address, _, subscriptionId ->
                    asked += address to subscriptionId
                    SpamCheckResult(isSpam = address == "Unverified", matchSource = "carrier_label")
                }
            }

        assertEquals(
            listOf("Unverified" to 2, "+15550100001" to 1, "+15550100002" to null, "+15550100003" to null),
            asked,
        )
        assertEquals(listOf("Unverified"), result.spamMessages.map { it.number })
    }

    data class InboxRow(
        val address: String,
        val body: String,
        val date: Long,
        val subscriptionId: Int?,
    )

    class FakeSmsInbox : ContentProvider() {
        var rows: List<InboxRow> = emptyList()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: emptyArray()
            val result = MatrixCursor(columns)
            for (row in rows.sortedByDescending { it.date }) {
                result.addRow(
                    Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            "address" -> row.address
                            "body" -> row.body
                            "date" -> row.date
                            Telephony.Sms.SUBSCRIPTION_ID -> row.subscriptionId
                            else -> null
                        }
                    },
                )
            }
            return result
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
