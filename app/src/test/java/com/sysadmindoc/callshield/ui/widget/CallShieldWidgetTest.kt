package com.sysadmindoc.callshield.ui.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.NumberFormat

/** The widget redraws itself: refreshAll used to broadcast ACTION_APPWIDGET_UPDATE to its own provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallShieldWidgetTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `refreshAll with no placed widget starts nothing and sends no broadcast`() {
        assertNull(CallShieldWidget.refreshAll(application))

        assertTrue(shadowOf(application).broadcastIntents.isEmpty())
    }

    @Test
    fun `refreshAll renders a placed widget directly`() {
        val dao = AppDatabase.getInstance(application).spamDao()
        runBlocking { dao.insertBlockedCall(BlockedCall(number = "+12125550188")) }
        val manager = AppWidgetManager.getInstance(application)
        val widgetId = shadowOf(manager).createWidget(CallShieldWidget::class.java, R.layout.widget_callshield)

        runBlocking { requireNotNull(CallShieldWidget.refreshAll(application)).join() }

        // The layout's placeholder reads "0 total blocked", so a count of one
        // or more proves the widget was rendered from the database.
        val total = runBlocking { dao.getBlockedCountSinceSync(0) }
        assertTrue(total >= 1)
        val view = shadowOf(manager).getViewFor(widgetId)
        val shown = view.findViewById<TextView>(R.id.widget_total).text
        val expected = application.getString(R.string.widget_total_blocked, NumberFormat.getIntegerInstance().format(total))
        assertEquals(expected, shown.toString())
        assertTrue(shadowOf(application).broadcastIntents.isEmpty())
    }
}
