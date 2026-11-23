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
            val toggledState = PrivilegedToggleHelper.toggleSilently(
                context,
                QuickTileAutomation.QuickTileTarget.MOBILE_DATA
            )
            if (toggledState != null) {
                Toast.makeText(
                    context,
                    "Mobile Data ${if (toggledState) "ON" else "OFF"}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val a11y = SmartDotAccessibilityService.instance
            if (a11y != null && !SmartDotAccessibilityService.isUserPaused) {
                a11y.performQuickTileToggle(QuickTileAutomation.QuickTileTarget.MOBILE_DATA) { ok ->
                    if (!ok) {
                        openFallbackConnectivityPanel(context)
                    }
                }
                return
            }

            openFallbackConnectivityPanel(context)
        }

        private fun openFallbackConnectivityPanel(context: Context) {
            val launched = runCatching {
                context.startActivity(
                    QuickTileAutomation.buildFallbackSettingsIntent(
                        QuickTileAutomation.QuickTileTarget.MOBILE_DATA
                    )
                )
                true
            }.getOrDefault(false)
            if (!launched) {
                Toast.makeText(context, "Unable to open Mobile Data settings.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
