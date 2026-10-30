package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Robolectric coverage for [SpamActionReceiver] — drives the real
 * BroadcastReceiver against the framework NotificationManager, off-device.
 *
 * The notification cancel happens synchronously inside `onReceive` (before the
 * async repo/community work), so we can assert it deterministically without
 * awaiting the background coroutine. This locks the v1.7.14 fix where the
 * feedback notification was cancelled under the wrong ID.
 *
 * That background work reports the number, and with the real transport it
 * posted to the live Worker on every run, which committed the report to the
 * public repo. The fake keeps it local, and HttpClient's unit-test DNS refuses
 * the Worker's host for anything that outlives the swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpamActionReceiverRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val originalTransport = CommunityContributor.transport

    @Before
    fun keepReportsLocal() {
        CommunityContributor.transport = { ContributeResult(true, "faked in test", ContributeOutcome.REPORTED_SPAM) }
    }

    @After
    fun restoreTransport() {
        CommunityContributor.transport = originalTransport
    }

    private fun postFeedbackNotification(number: String): Int {
        val id = NotificationHelper.feedbackNotificationId(number)
        nm.createNotificationChannel(
            NotificationChannel("test", "test", NotificationManager.IMPORTANCE_LOW),
        )
        val notif =
            android.app.Notification
                .Builder(context, "test")
                .setContentTitle("after-call")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        nm.notify(id, notif)
        return id
    }

    @Test
    fun `FEEDBACK_SPAM cancels the feedback notification by its stable id`() {
        val number = "+15551234567"
        val id = postFeedbackNotification(number)
        assertTrue(shadowOf(nm).getNotification(id) != null)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.FEEDBACK_SPAM").putExtra("number", number),
        )

        assertFalse(
            "feedback notification must be cancelled under feedbackNotificationId",
            shadowOf(nm).getNotification(id) != null,
        )
    }

    /**
     * The post-call screen calls this directly instead of broadcasting
     * FEEDBACK_SPAM to the receiver, so the prompt must go at once, nothing
     * may be broadcast, and the returned work must block and report.
     *
     * The other tests here leave their receiver work running on the app
     * scope, and it reports through the same fake transport, so only this
     * number's reports are compared. It uses the shared repository like
     * those tests do, so their late writes never land on a fixture that is
     * being closed.
     */
    @Test
    fun `spam feedback shared with the post-call screen blocks and reports without a broadcast`() {
        val number = "+15553330002"
        val id = postFeedbackNotification(number)
        val sent = mutableListOf<CommunityReport>()
        CommunityContributor.transport = { report ->
            synchronized(sent) { sent += report }
            ContributeResult(true, "faked in test", ContributeOutcome.REPORTED_SPAM)
        }

        val work = SpamActionReceiver.spamFeedback(context, number)

        assertFalse("the after-call prompt goes at once", shadowOf(nm).getNotification(id) != null)
        assertTrue("nothing is broadcast", shadowOf(context as Application).broadcastIntents.isEmpty())
        runBlocking { work() }
        val repository = SpamRepository.getInstance(context)
        assertEquals("user_blocklist", runBlocking { repository.isSpam(number) }.matchSource)
        val ours = synchronized(sent) { sent.filter { it.number == number } }
        assertEquals(listOf(number to "spam"), ours.map { it.number to it.type })
    }

    /** The post-call screen has already closed when this runs, so a failed write must show the toast and throw nothing. */
    @Test
    fun `a failed action shows the failure toast instead of throwing`() {
        ShadowToast.reset()
        var escaped: Throwable? = null
        // Off the main thread, since runAction posts its toast to it.
        val worker =
            thread {
                try {
                    runBlocking { SpamActionReceiver.runAction(context) { error("disk full") } }
                } catch (e: Throwable) {
                    escaped = e
                }
            }
        val deadline = System.currentTimeMillis() + 5_000
        while (worker.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        worker.join(1_000)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("runAction finished", worker.isAlive)
        assertNull("nothing escaped runAction", escaped)
        assertEquals(context.getString(R.string.notification_action_failed), ShadowToast.getTextOfLatestToast())
    }

    /**
     * Called straight through onReceive like the tests here, goAsync() has no
     * broadcast to hand back, so the work's last step must not assume one. When
     * it did, the NullPointerException left the app scope after the test ended,
     * and the next runTest in the run, in whatever class, failed with
     * UncaughtExceptionsBeforeTest. The first runTest turns on the collector
     * that holds it. The transport waits for the latch, so the launched job is
     * still running when the scope's children are read.
     */
    @Test
    fun `an action called without a broadcast finishes without throwing into the app scope`() {
        runTest { }
        val reporting = CountDownLatch(1)
        CommunityContributor.transport = {
            reporting.await(5, TimeUnit.SECONDS)
            ContributeResult(true, "faked in test", ContributeOutcome.REPORTED_SPAM)
        }
        val appJob = CallShieldApp.appScope.coroutineContext.job
        val before = appJob.children.toSet()

        SpamActionReceiver().onReceive(
            context,
            Intent(SpamActionReceiver.ACTION_FEEDBACK_SPAM).putExtra(SpamActionReceiver.EXTRA_FEEDBACK_NUMBER, "+15553330003"),
        )
        val launched = appJob.children.toSet() - before
        reporting.countDown()
        assertEquals(1, launched.size)
        // Bounded: a failed action posts its toast to this thread, so a plain
        // join would hang instead of failing.
        runBlocking { withTimeout(10_000) { launched.joinAll() } }

        runTest { }
    }

    @Test
    fun `FEEDBACK_NOT_SPAM cancels the same feedback notification`() {
        val number = "+15559876543"
        val id = postFeedbackNotification(number)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM").putExtra("number", number),
        )

        assertFalse(shadowOf(nm).getNotification(id) != null)
    }

    @Test
    fun `NOT_SPAM on a blocked-call alert cancels that alert`() {
        val number = "+15554440001"
        val id = postFeedbackNotification(number)

        SpamActionReceiver().onReceive(
            context,
            Intent(NotificationHelper.ACTION_NOT_SPAM)
                .putExtra(NotificationHelper.EXTRA_NUMBER, number)
                .putExtra(NotificationHelper.EXTRA_NOTIF_ID, id)
                .putExtra(NotificationHelper.EXTRA_REASON_CODE, "database"),
        )

        assertFalse(shadowOf(nm).getNotification(id) != null)
    }

    /**
     * A toast holds two lines on Android 12 and later, too few to say why a
     * refused Not spam changed nothing, so the reason takes the alert's place
     * and opens the number, where the block can be removed.
     */
    @Test
    fun `a Not spam the user's own block refuses leaves the reason where the alert was`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowToast.reset()
        val number = "+15554440002"
        val repository = SpamRepository.getInstance(context)
        runBlocking { repository.blockNumber(number, "spam", "blocked in test") }
        val id = postFeedbackNotification(number)
        val appJob = CallShieldApp.appScope.coroutineContext.job
        val before = appJob.children.toSet()
        try {
            SpamActionReceiver().onReceive(
                context,
                Intent(NotificationHelper.ACTION_NOT_SPAM)
                    .putExtra(NotificationHelper.EXTRA_NUMBER, number)
                    .putExtra(NotificationHelper.EXTRA_NOTIF_ID, id)
                    .putExtra(NotificationHelper.EXTRA_REASON_CODE, "database"),
            )
            val launched = appJob.children.toSet() - before
            assertEquals(1, launched.size)
            // The work posts its toast to this thread, so it's idled until the work ends.
            val deadline = System.currentTimeMillis() + 10_000
            while (launched.any { it.isActive } && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(context.getString(R.string.notif_not_spam_refused), ShadowToast.getTextOfLatestToast())
            val reason = shadowOf(nm).getNotification(id) ?: error("nothing took the alert's place")
            assertEquals(context.getString(R.string.notif_not_spam_refused_text), reason.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals(number, shadowOf(reason.contentIntent).savedIntent.getStringExtra("open_number"))
        } finally {
            runBlocking { repository.unblockByNumber(number) }
        }
    }

    @Test
    fun `unknown action is a no-op and leaves notifications intact`() {
        val number = "+15550000000"
        val id = postFeedbackNotification(number)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.UNKNOWN").putExtra("number", number),
        )

        assertTrue(shadowOf(nm).getNotification(id) != null)
    }
}
