package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.checker.PipelineTraceVerdict
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CallFrequencyEvidenceTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var provider: FakeCallLog
    private val number = "+14652340101"
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCallLog::class.java, CallLog.Calls.CONTENT_URI.authority)
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `quiet hours contacts only and diagnostic rows do not escalate`() =
        runBlocking {
            fixture.repository.setMlScorer(false)
            fixture.repository.setHeuristics(false)
            for ((reasonIndex, reason) in listOf("time_block", "contacts_only", "pipeline_diagnostic").withIndex()) {
                fixture.dao.insertBlockedCalls(
                    (1..3).map { index ->
                        BlockedCall(
                            number = number,
                            timestamp = now - (reasonIndex * 3 + index) * 60_000L,
                            matchReason = reason,
                            wasBlocked = reason != "pipeline_diagnostic",
                            logKey = "$reason-$index",
                        )
                    },
                )
                assertEquals(reason, PipelineTraceVerdict.PASS, frequencyVerdict())
            }

            assertEquals(6, fixture.dao.getBlockedCallTimesSince(number, now - 7 * 86_400_000L).size)
            assertFalse(fixture.repository.isSpam(number).isSpam)
        }

    @Test
    fun `three calls that rang trigger frequency escalation`() =
        runBlocking {
            provider.rows =
                listOf(
                    CallRow(number, now - 60_000L, CallLog.Calls.INCOMING_TYPE),
                    CallRow(number.drop(2), now - 120_000L, CallLog.Calls.MISSED_TYPE),
                    CallRow(number, now - 180_000L, CallLog.Calls.MISSED_TYPE),
                    CallRow(number, now - 240_000L, CallLog.Calls.BLOCKED_TYPE),
                )

            assertEquals(PipelineTraceVerdict.BLOCK, frequencyVerdict())
        }

    @Test
    fun `silenced missed calls and matching local block rows are ignored`() =
        runBlocking {
            val silenced = now - 60_000L
            val loggedBlock = now - 120_000L
            fixture.dao.insertBlockedCall(
                BlockedCall(number = number, timestamp = loggedBlock + 1000L, matchReason = "time_block", logKey = "quiet"),
            )
            provider.rows =
                listOf(
                    CallRow(number, silenced, CallLog.Calls.MISSED_TYPE, CallLog.Calls.USER_MISSED_CALL_SCREENING_SERVICE_SILENCED),
                    CallRow(number, loggedBlock, CallLog.Calls.MISSED_TYPE),
                    CallRow(number, now - 180_000L, CallLog.Calls.MISSED_TYPE),
                    CallRow("+493012340101", now - 240_000L, CallLog.Calls.MISSED_TYPE),
                    CallRow(number, now - 300_000L, CallLog.Calls.MISSED_TYPE, CallLog.Calls.USER_MISSED_DND_MODE),
                    CallRow(number, now - 360_000L, CallLog.Calls.INCOMING_TYPE),
                    CallRow(number, now - 420_000L, CallLog.Calls.MISSED_TYPE, CallLog.Calls.AUTO_MISSED_MAXIMUM_RINGING),
                )

            assertEquals(PipelineTraceVerdict.PASS, frequencyVerdict())
        }

    @Test
    @Config(sdk = [29])
    fun `older Android excludes silenced calls using local block times`() =
        runBlocking {
            val times = (1..3).map { now - it * 60_000L }
            fixture.dao.insertBlockedCalls(
                times.mapIndexed { index, timestamp ->
                    BlockedCall(number = number, timestamp = timestamp + 1000L, matchReason = "time_block", logKey = "old-$index")
                },
            )
            provider.rows = times.map { CallRow(number, it, CallLog.Calls.MISSED_TYPE) }

            assertEquals(PipelineTraceVerdict.PASS, frequencyVerdict())
        }

    @Test
    fun `international call log digits still match the same caller`() =
        runBlocking {
            val foreign = "+493012340101"
            provider.rows = (1..3).map { CallRow(foreign.drop(1), now - it * 60_000L, CallLog.Calls.MISSED_TYPE) }

            assertEquals(PipelineTraceVerdict.BLOCK, frequencyVerdict(foreign))
        }

    private suspend fun frequencyVerdict(caller: String = number): PipelineTraceVerdict {
        val trace = fixture.repository.traceRules(caller)
        return trace.entries.single { it.checkerName == "frequency" }.verdict
    }

    data class CallRow(
        val number: String,
        val timestamp: Long,
        val type: Int,
        val missedReason: Long = 0L,
    )

    class FakeCallLog : ContentProvider() {
        var rows: List<CallRow> = emptyList()

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
            if (selection != "${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?") {
                return result
            }
            val args = requireNotNull(selectionArgs)
            for (row in rows) {
                if (row.type.toString() !in args.take(2) || row.timestamp <= args[2].toLong() || !row.number.endsWith(args[3].drop(1))) {
                    continue
                }
                val values =
                    Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            CallLog.Calls.NUMBER -> row.number
                            CallLog.Calls.DATE -> row.timestamp
                            CallLog.Calls.TYPE -> row.type
                            CallLog.Calls.MISSED_REASON -> row.missedReason
                            else -> null
                        }
                    }
                result.addRow(values)
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
