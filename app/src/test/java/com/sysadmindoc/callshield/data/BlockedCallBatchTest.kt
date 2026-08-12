package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlockedCallBatchTest {
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `cursor batch query preserves id order when timestamps tie`() =
        runBlocking {
            val rows =
                (1..513).map { id ->
                    BlockedCall(
                        id = id.toLong(),
                        number = "+1212555${id.toString().padStart(4, '0')}",
                        timestamp = 1_735_689_600_000L,
                    )
                }
            fixture.dao.insertBlockedCalls(rows)

            val result = mutableListOf<BlockedCall>()
            var beforeTimestamp = Long.MAX_VALUE
            var beforeId = Long.MAX_VALUE
            while (true) {
                val batch = fixture.dao.readBlockedCallsBatch(beforeTimestamp, beforeId, 100)
                if (batch.isEmpty()) break
                result += batch
                val last = batch.last()
                beforeTimestamp = last.timestamp
                beforeId = last.id
            }

            assertEquals(513, result.size)
            assertEquals((513 downTo 1).toList(), result.map { it.id.toInt() })
        }
}
