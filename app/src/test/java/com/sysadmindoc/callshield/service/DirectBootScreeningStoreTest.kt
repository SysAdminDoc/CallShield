package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectBootScreeningStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DirectBootScreeningStore.clearForTest(context)
    }

    @Test
    fun `device protected mirror preserves active explicit blocks and preferences`() =
        runBlocking {
            val now = System.currentTimeMillis()
            DirectBootScreeningStore.write(
                context = context,
                blockedNumbers =
                    listOf(
                        SpamNumber(number = "+12125550101", type = "test", isUserBlocked = true),
                        SpamNumber(
                            number = "+12125550102",
                            type = "test",
                            isUserBlocked = true,
                            expiresAt = now + 60_000L,
                        ),
                        SpamNumber(number = "+12125550103", type = "test", isUserBlocked = false),
                    ),
                blockCallsEnabled = true,
                blockUnknownEnabled = true,
                silentVoicemailEnabled = true,
            )

            val snapshot = DirectBootScreeningStore.read(context)

            assertTrue(snapshot.ready)
            assertTrue(snapshot.blockCallsEnabled)
            assertTrue(snapshot.blockUnknownEnabled)
            assertTrue(snapshot.silentVoicemailEnabled)
            assertTrue(snapshot.isBlocked("+12125550101", now))
            assertTrue(snapshot.isBlocked("+12125550102", now))
            assertFalse(snapshot.isBlocked("+12125550102", now + 120_000L))
            assertFalse(snapshot.isBlocked("+12125550103", now))
        }

    @Test
    fun `missing direct boot mirror fails open`() {
        DirectBootScreeningStore.clearForTest(context)

        val snapshot = DirectBootScreeningStore.read(context)

        assertFalse(snapshot.ready)
        assertFalse(snapshot.isBlocked("+12125550101"))
    }
}
