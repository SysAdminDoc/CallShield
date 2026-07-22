package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsReceiverRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = SpamRepository.getInstance(context)
    private val coroutineFailure = AtomicReference<Throwable?>()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable -> coroutineFailure.set(throwable) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
        runBlocking { repository.setBlockSms(false) }
    }

    @After
    fun tearDown() {
        SmsContentAnalyzer.updateSpamDomains(emptySet())
        scope.cancel()
        notificationManager.cancelAll()
    }

    @Test
    fun `onReceive warns about phishing URLs even when SMS blocking is disabled`() {
        val receiver =
            SmsReceiver().apply {
                repo = repository
                applicationScope = scope
            }
        val body = "Review your account at https://evil.test/login"
        SmsContentAnalyzer.updateSpamDomains(setOf("evil.test"))
        assertTrue(runBlocking { UrlSafetyChecker.checkSmsBody(body).isNotEmpty() })
        val intent =
            Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                putExtra("format", "3gpp")
                putExtra("pdus", arrayOf(buildDeliverPdu("15551234567", body)))
            }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        assertEquals(1, messages.size)
        assertEquals(body, messages.single().messageBody)

        val pendingResult =
            ReflectionHelpers.callConstructor(
                android.content.BroadcastReceiver.PendingResult::class.java,
            )
        ReflectionHelpers.setField(receiver, "mPendingResult", pendingResult)
        receiver.onReceive(context, intent)

        val notification = awaitPhishingNotification()
        assertEquals(
            context.getString(com.sysadmindoc.callshield.R.string.notif_phishing_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(
            notification.extras
                .getString(Notification.EXTRA_TEXT)
                .orEmpty()
                .contains("known_spam_domain"),
        )
    }

    private fun awaitPhishingNotification(): Notification =
        runBlocking {
            withTimeout(5_000L) {
                while (shadowOf(notificationManager).allNotifications.isEmpty()) {
                    coroutineFailure.get()?.let { throw AssertionError("SMS receiver coroutine failed", it) }
                    delay(10L)
                }
                shadowOf(notificationManager).allNotifications.single()
            }
        }

    /** Build a minimal 3GPP SMS-DELIVER PDU with a UCS-2 message body. */
    private fun buildDeliverPdu(
        sender: String,
        body: String,
    ): ByteArray {
        val addressDigits = if (sender.length % 2 == 0) sender else sender + "F"
        val semiOctets =
            addressDigits
                .chunked(2)
                .map { pair -> pair.reversed().toInt(16).toByte() }
        val userData = body.toByteArray(StandardCharsets.UTF_16BE)
        return buildList {
            add(0x00.toByte()) // No SMSC address.
            add(0x04.toByte()) // SMS-DELIVER.
            add(sender.length.toByte())
            add(0x91.toByte()) // International address.
            addAll(semiOctets)
            add(0x00.toByte()) // PID.
            add(0x08.toByte()) // UCS-2 DCS.
            addAll(byteArrayOf(0x62, 0x70, 0x12, 0x21, 0x43, 0x65, 0x00).toList())
            add(userData.size.toByte())
            addAll(userData.toList())
        }.toByteArray()
    }
}
