package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryMeetingModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `picking and unpicking meeting apps keeps only catalog packages`() =
        runBlocking {
            IsolatedRepositoryFixture(context).use { fixture ->
                val repository = fixture.repository

                repository.setMeetingModeApp("us.zoom.videomeetings", true)
                repository.setMeetingModeApp("com.microsoft.teams", true)
                repository.setMeetingModeApp("com.example.recorder", true)
                assertEquals(setOf("us.zoom.videomeetings", "com.microsoft.teams"), repository.meetingModeApps.first())

                repository.setMeetingModeApp("us.zoom.videomeetings", false)
                repository.setMeetingModeApp("com.microsoft.teams", false)
                assertEquals(emptySet<String>(), repository.meetingModeApps.first())
            }
        }

    @Test
    fun `meeting mode is off until turned on`() =
        runBlocking {
            IsolatedRepositoryFixture(context).use { fixture ->
                assertEquals(false, fixture.repository.meetingModeEnabled.first())
                fixture.repository.setMeetingMode(true)
                assertEquals(true, fixture.repository.meetingModeEnabled.first())
            }
        }
}
