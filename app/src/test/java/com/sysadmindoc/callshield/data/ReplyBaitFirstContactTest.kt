package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reply bait reaches a live SMS only as a stranger's first text, read off the real inbox and sent folders. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReplyBaitFirstContactTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var provider: FakeSms
    private val number = "+14652340101"
    private val body = "Hi mum, my phone broke. This is my new number, can you text me back?"
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeSms::class.java, Telephony.Sms.CONTENT_URI.authority)
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
        runBlocking {
            fixture.repository.setHeuristics(false)
            fixture.repository.setMlScorer(false)
            fixture.repository.setAggressiveMode(true)
        }
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a stranger's first text that fishes for a reply is flagged in aggressive mode`() =
        runBlocking {
            val result = fixture.repository.isSpamSms(number, body)

            assertTrue(result.isSpam)
            assertEquals("sms_content", result.matchSource)
            assertTrue(result.signals.toString(), "reply_bait" in result.signals)
        }

    @Test
    fun `the arriving message already in the inbox doesn't make it a second text`() =
        runBlocking {
            provider.inbox = listOf(number to now - 10_000L)

            assertTrue("reply_bait" in fixture.repository.isSpamSms(number, body).signals)
        }

    @Test
    fun `by default it takes a second signal to block`() =
        runBlocking {
            fixture.repository.setAggressiveMode(false)

            assertFalse(fixture.repository.isSpamSms(number, body).isSpam)
        }

    @Test
    fun `a number that texted before is no stranger`() =
        runBlocking {
            provider.inbox = listOf(number.drop(2) to now - 3_600_000L)

            assertFalse(fixture.repository.isSpamSms(number, body).isSpam)
        }

    @Test
    fun `a number the user texted is no stranger`() =
        runBlocking {
            provider.sent = listOf(number)

            assertFalse(fixture.repository.isSpamSms(number, body).isSpam)
        }

    @Test
    fun `a rescan of an old message doesn't treat it as a first text`() =
        runBlocking {
            assertFalse(fixture.repository.isSpamSms(number, body, realtimeCall = false).isSpam)
        }

    @Test
    fun `a short code is not a stranger's phone`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SmsContextChecker.shared.isFirstMessageFrom(context, "72345"))
        assertTrue(SmsContextChecker.shared.isFirstMessageFrom(context, number))
    }

    /** The inbox and sent folders, answering the two queries the SMS checks make. */
    class FakeSms : ContentProvider() {
        var inbox: List<Pair<String, Long>> = emptyList()
        var sent: List<String> = emptyList()

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
            val args = selectionArgs.orEmpty()
            val rows =
                when {
                    uri == Telephony.Sms.Sent.CONTENT_URI && selection == "${Telephony.Sms.Sent.ADDRESS} LIKE ?" -> {
                        sent.filter { it.endsWith(args[0].drop(1)) }.map { it to 0L }
                    }

                    uri == Telephony.Sms.Inbox.CONTENT_URI &&
                        selection == "${Telephony.Sms.Inbox.ADDRESS} LIKE ? AND ${Telephony.Sms.Inbox.DATE} < ?" -> {
                        inbox.filter { (address, date) -> address.endsWith(args[0].drop(1)) && date < args[1].toLong() }
                    }

                    uri == Telephony.Sms.Inbox.CONTENT_URI && selection == "${Telephony.Sms.Inbox.DATE} > ?" -> {
                        inbox.filter { (_, date) -> date > args[0].toLong() }
                    }

                    else -> {
                        emptyList()
                    }
                }
            rows.forEach { (address, date) ->
                result.addRow(
                    Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            Telephony.Sms.ADDRESS -> address
                            Telephony.Sms.DATE -> date
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
