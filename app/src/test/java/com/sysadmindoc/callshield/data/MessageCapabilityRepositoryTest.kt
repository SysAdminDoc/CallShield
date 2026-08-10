package com.sysadmindoc.callshield.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageCapabilityRepositoryTest {
    @Test
    fun `latest capability state persists without message data`() {
        val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
        try {
            val status =
                MessageCapabilityStatus(
                    source = MessageCapabilitySource.SMS_BROADCAST,
                    state = MessageCapabilityState.SENDER_ONLY,
                    apiLevel = 36,
                    observedAtMillis = 123L,
                    latencyMillis = 456L,
                )

            runBlocking {
                fixture.repository.recordMessageCapability(status)
                assertEquals(status, fixture.repository.smsMessageCapabilityStatus.first())
            }
        } finally {
            fixture.close()
        }
    }
}
