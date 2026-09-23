package com.sysadmindoc.callshield

import android.content.ComponentName
import android.content.Context
import android.telecom.Connection
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckContext
import com.sysadmindoc.callshield.data.checker.EmergencyNumberFloorChecker
import com.sysadmindoc.callshield.data.checker.StirShakenChecker
import com.sysadmindoc.callshield.data.checker.TimeBlockChecker
import com.sysadmindoc.callshield.data.checker.VerificationMessageFloorChecker
import com.sysadmindoc.callshield.service.CallShieldTileService
import com.sysadmindoc.callshield.service.RcsNotificationListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text the system shows (Settings labels, block descriptions, the digest)
 * has to come from resources to follow the app language. Run in Chinese, so a
 * hard-coded English literal can't pass for a resource that happens to match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class LocalizedSystemTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ctx(
        number: String = "+12122340101",
        smsBody: String? = null,
        prefs: Preferences = emptyPreferences(),
        verificationStatus: Int? = null,
    ) = CheckContext(
        appContext = context,
        number = number,
        smsBody = smsBody,
        realtimeCall = true,
        prefs = prefs,
        verificationStatus = verificationStatus,
    )

    @Test
    fun `the tile and notification-listener labels are resources`() {
        val pm = context.packageManager
        val listener = pm.getServiceInfo(ComponentName(context, RcsNotificationListener::class.java), 0)
        val tile = pm.getServiceInfo(ComponentName(context, CallShieldTileService::class.java), 0)

        assertEquals(R.string.notification_listener_label, listener.labelRes)
        assertEquals(R.string.app_name, tile.labelRes)
        assertEquals(context.getString(R.string.notification_listener_label), listener.loadLabel(pm).toString())
        assertNotEquals("CallShield RCS Filter", listener.loadLabel(pm).toString())
    }

    @Test
    fun `checker descriptions follow the app language`() =
        runBlocking {
            val quietHours =
                preferencesOf(
                    SpamRepository.KEY_TIME_BLOCK to true,
                    SpamRepository.KEY_TIME_BLOCK_START to 8,
                    SpamRepository.KEY_TIME_BLOCK_END to 8,
                )

            @Suppress("DEPRECATION")
            val failed = Connection.VERIFICATION_STATUS_FAILED

            assertEquals(
                context.getString(R.string.block_reason_quiet_hours),
                TimeBlockChecker().check(ctx(prefs = quietHours))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_carrier_unverified),
                StirShakenChecker().check(ctx(verificationStatus = failed))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_emergency_floor),
                EmergencyNumberFloorChecker().check(ctx(number = "911"))?.description,
            )
            assertEquals(
                context.getString(R.string.block_reason_verification_floor),
                VerificationMessageFloorChecker(SmsContentAnalyzer())
                    .check(ctx(smsBody = "Your verification code is 482913."))
                    ?.description,
            )
            assertNotEquals("Blocked during quiet hours", context.getString(R.string.block_reason_quiet_hours))
        }

    @Test
    fun `the Chinese digest keeps its line breaks`() {
        // A literal line break inside a string resource collapses to a space,
        // which ran the whole Chinese digest together on one line.
        assertEquals(3, context.getString(R.string.digest_big_text, 3, 2, 1, "x").lines().size)
    }
}
