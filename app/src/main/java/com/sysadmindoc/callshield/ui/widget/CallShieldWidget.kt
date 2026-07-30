package com.sysadmindoc.callshield.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.ui.MainActivity
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
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Hold the broadcast open for the duration of the async data load.
        // Without this the process drops to cached priority as soon as
        // onUpdate returns and can be killed mid-query, leaving the widget on
        // layout placeholders until the next 30-minute cycle.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CallShieldApp.appScope.launch {
            try {
                val manager = AppWidgetManager.getInstance(appContext)
                for (id in appWidgetIds) {
                    updateWidget(appContext, manager, id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun updateWidget(
        appContext: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val views = RemoteViews(appContext.packageName, R.layout.widget_callshield)

        // Entire widget opens the app
        val intent =
            PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_root, intent)

        try {
            val dao = AppDatabase.getInstance(appContext).spamDao()
            val repo = SpamRepository.getInstance(appContext)

            // Calculate start-of-today and start-of-yesterday. Yesterday
            // is derived via Calendar so DST-transition days (23/25 h)
            // don't skew the trend comparison window.
            val now = System.currentTimeMillis()
            val todayCalendar =
                Calendar
                    .getInstance()
                    .apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
            val todayStart = todayCalendar.timeInMillis
            val yesterdayStart = todayCalendar.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis

            val todayCount = dao.getBlockedCountBetweenSync(todayStart, now)
            val yesterdayCount = dao.getBlockedCountBetweenSync(yesterdayStart, todayStart)
            val totalCount = dao.getBlockedCountSinceSync(0)
            val numberFormatter = NumberFormat.getIntegerInstance()
            val localizedTodayCount = numberFormatter.format(todayCount)
            val localizedTotalCount = numberFormatter.format(totalCount)

            // Trend arrow: compare today vs yesterday
            val trendText =
                when {
                    todayCount > yesterdayCount -> appContext.getString(R.string.widget_today_trend_up, localizedTodayCount)
                    todayCount < yesterdayCount -> appContext.getString(R.string.widget_today_trend_down, localizedTodayCount)
                    else -> appContext.getString(R.string.widget_today_trend_same, localizedTodayCount)
                }

            // Last blocked time
            val lastTimestamp = dao.getLastBlockedTimestamp()
            val lastBlockedText = formatLastBlocked(appContext, lastTimestamp, now)

            // Protection status
            val callsEnabled = repo.blockCallsEnabled.first()
            val smsEnabled = repo.blockSmsEnabled.first()
            val isActive = callsEnabled || smsEnabled

            views.setTextViewText(R.id.widget_blocked_today, localizedTodayCount)
            views.setTextViewText(R.id.widget_trend, trendText)
            views.setTextViewText(
                R.id.widget_total,
                appContext.getString(R.string.widget_total_blocked, localizedTotalCount),
            )
            views.setTextViewText(R.id.widget_last_blocked, lastBlockedText)

            // Update status text and title color based on protection state.
            // Resolve the accent via getColor so it picks the light/dark variant
            // (values / values-night) that matches the launcher configuration.
            val accent =
                if (isActive) {
                    appContext.getColor(R.color.widget_status_active)
                } else {
                    appContext.getColor(R.color.widget_status_off)
                }
            views.setTextViewText(
                R.id.widget_status,
                appContext.getString(
                    if (isActive) R.string.widget_protection_active else R.string.widget_protection_off,
                ),
            )
            views.setTextColor(R.id.widget_title, accent)
            views.setTextColor(R.id.widget_status, accent)

            manager.updateAppWidget(widgetId, views)
        } catch (_: Exception) {
            // Leave whatever the widget is currently showing. Pushing the
            // freshly-inflated RemoteViews here would replace real counts with
            // the layout's "0 blocked / never" placeholders.
        }
    }

    /**
     * Formats the last-blocked timestamp into a human-readable relative string.
     */
    private fun formatLastBlocked(
        context: Context,
        timestamp: Long?,
        now: Long,
    ): String {
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
            val intent =
                Intent(context, CallShieldWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                }
            val ids =
                AppWidgetManager
                    .getInstance(context)
                    .getAppWidgetIds(ComponentName(context, CallShieldWidget::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
