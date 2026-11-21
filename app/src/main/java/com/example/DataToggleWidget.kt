package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

/**
 * 1x1 Home Screen Data ON/OFF Widget.
 * Tapping triggers SmartDotAccessibilityService Mobile Data quick-tile automation,
 * or opens the system Internet Connectivity panel when Accessibility isn't active.
 */
class DataToggleWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidgetView(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE_DATA) {
            handleWidgetTap(context)
        }
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE_DATA = "com.example.ACTION_WIDGET_TOGGLE_DATA"

        fun updateWidgetView(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_data_toggle)
            val toggleIntent = Intent(context, DataToggleWidget::class.java).apply {
                action = ACTION_WIDGET_TOGGLE_DATA
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_data_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun handleWidgetTap(context: Context) {
            val service = SmartDotAccessibilityService.instance
            if (service != null) {
                service.performQuickTileToggle(QuickTileAutomation.QuickTileTarget.MOBILE_DATA) { toggled ->
                    if (!toggled) {
                        runCatching {
                            context.startActivity(
                                QuickTileAutomation.buildFallbackSettingsIntent(
                                    QuickTileAutomation.QuickTileTarget.MOBILE_DATA
                                )
                            )
                        }
                    }
                }
            } else {
                val launched = runCatching {
                    context.startActivity(
                        QuickTileAutomation.buildFallbackSettingsIntent(
                            QuickTileAutomation.QuickTileTarget.MOBILE_DATA
                        )
                    )
                    true
                }.getOrDefault(false)
                if (!launched) {
                    Toast.makeText(context, "Enable Accessibility Service for 1-tap Data toggle.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
