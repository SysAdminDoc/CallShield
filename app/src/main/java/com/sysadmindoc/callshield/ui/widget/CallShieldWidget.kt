package com.sysadmindoc.callshield.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Feature 6: Home screen widget.
 * Shows shield status, blocked count today, and total blocked.
 */
class CallShieldWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_callshield)

        // Open app on click
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_title, intent)
        views.setOnClickPendingIntent(R.id.widget_blocked_today, intent)

        // Fetch counts async
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).spamDao()
                val todaySince = System.currentTimeMillis() - 86_400_000
                val recentBlocked = dao.getRecentBlockedNumbers(todaySince)
                val todayCount = recentBlocked.count { it.wasBlocked }

                // Get total from all time
                val allRecent = dao.getRecentBlockedNumbers(0)
                val totalCount = allRecent.count { it.wasBlocked }

                views.setTextViewText(R.id.widget_blocked_today, todayCount.toString())
                views.setTextViewText(R.id.widget_total, "$totalCount total blocked")
                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
        }

        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        fun refreshAll(context: Context) {
            val intent = Intent(context, CallShieldWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, CallShieldWidget::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
