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
import java.text.NumberFormat
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Home screen widget.
 * Shows shield status, protection state, blocked count with trend arrow,
 * total blocked, and time since last blocked call.
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

                // Calculate start-of-today and start-of-yesterday
                val now = System.currentTimeMillis()
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val yesterdayStart = todayStart - 86_400_000L

                val todayCount = dao.getBlockedCountBetweenSync(todayStart, now)
                val yesterdayCount = dao.getBlockedCountBetweenSync(yesterdayStart, todayStart)
                val totalCount = dao.getBlockedCountSinceSync(0)
                val numberFormatter = NumberFormat.getIntegerInstance()
                val localizedTodayCount = numberFormatter.format(todayCount)
                val localizedTotalCount = numberFormatter.format(totalCount)

                // Trend arrow: compare today vs yesterday
                val trendText = when {
                    todayCount > yesterdayCount -> context.getString(R.string.widget_today_trend_up, localizedTodayCount)
                    todayCount < yesterdayCount -> context.getString(R.string.widget_today_trend_down, localizedTodayCount)
                    else -> context.getString(R.string.widget_today_trend_same, localizedTodayCount)
                }

                // Last blocked time
                val lastTimestamp = dao.getLastBlockedTimestamp()
                val lastBlockedText = formatLastBlocked(context, lastTimestamp, now)

                // Protection status
                val callsEnabled = repo.blockCallsEnabled.first()
                val smsEnabled = repo.blockSmsEnabled.first()
                val isActive = callsEnabled || smsEnabled

                views.setTextViewText(R.id.widget_blocked_today, localizedTodayCount)
                views.setTextViewText(R.id.widget_trend, trendText)
                views.setTextViewText(
                    R.id.widget_total,
                    context.getString(R.string.widget_total_blocked, localizedTotalCount)
                )
                views.setTextViewText(R.id.widget_last_blocked, lastBlockedText)

                // Update status text and title color based on protection state
                if (isActive) {
                    views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_protection_active))
                    views.setTextColor(R.id.widget_title, 0xFFA6E3A1.toInt()) // CatGreen
                    views.setTextColor(R.id.widget_status, 0xFFA6E3A1.toInt())
                } else {
                    views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_protection_off))
                    views.setTextColor(R.id.widget_title, 0xFFF38BA8.toInt()) // CatRed
                    views.setTextColor(R.id.widget_status, 0xFFF38BA8.toInt())
                }

                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {
                // Widget will show stale data — acceptable
            }
        }
    }

    /**
     * Formats the last-blocked timestamp into a human-readable relative string.
     */
    private fun formatLastBlocked(context: Context, timestamp: Long?, now: Long): String {
        if (timestamp == null || timestamp == 0L) {
            return context.getString(R.string.widget_last_never)
        }
        val diffMs = now - timestamp
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)

        return when {
            minutes < 1 -> context.getString(R.string.widget_last_just_now)
            minutes < 60 -> context.getString(R.string.widget_last_minutes_ago, minutes.toInt())
            hours < 24 -> context.getString(R.string.widget_last_hours_ago, hours.toInt())
            else -> context.getString(R.string.widget_last_days_ago, days.toInt())
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
