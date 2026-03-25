package com.sysadmindoc.callshield.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Home screen widget.
 * Shows shield status, protection state, blocked count today, and total blocked.
 * Entire widget is clickable — opens the app.
 */
class CallShieldWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_callshield)

        // Entire widget opens the app
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, intent)

        // Set click listeners immediately, data updates async
        manager.updateAppWidget(widgetId, views)

        // Fetch counts and protection status async
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).spamDao()
                val repo = SpamRepository.getInstance(context)
                val todaySince = System.currentTimeMillis() - 86_400_000
                val todayCount = dao.getBlockedCountSinceSync(todaySince)
                val totalCount = dao.getBlockedCountSinceSync(0)

                // Protection status
                val callsEnabled = repo.blockCallsEnabled.first()
                val smsEnabled = repo.blockSmsEnabled.first()
                val isActive = callsEnabled || smsEnabled

                views.setTextViewText(R.id.widget_blocked_today, todayCount.toString())
                views.setTextViewText(R.id.widget_total, "$totalCount total blocked")

                // Update status text and title color based on protection state
                if (isActive) {
                    views.setTextViewText(R.id.widget_status, "PROTECTION ACTIVE")
                    views.setTextColor(R.id.widget_title, 0xFFA6E3A1.toInt()) // CatGreen
                    views.setTextColor(R.id.widget_status, 0xFFA6E3A1.toInt())
                } else {
                    views.setTextViewText(R.id.widget_status, "PROTECTION OFF")
                    views.setTextColor(R.id.widget_title, 0xFFF38BA8.toInt()) // CatRed
                    views.setTextColor(R.id.widget_status, 0xFFF38BA8.toInt())
                }

                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {
                // Widget will show stale data — acceptable
            }
        }
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
