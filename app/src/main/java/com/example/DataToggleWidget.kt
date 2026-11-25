package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.Toast

/**
 * True 1-Tap Mobile Data Home Screen Widget:
 * Uses SmartDot's native toggle / built-in Accessibility Quick-Tile automation to toggle
 * Mobile Data in 1 tap, falling back gracefully to Android's Connectivity Settings Panel
 * when Accessibility is disabled or paused.
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
            appWidgetId: Int,
            forcedState: Boolean? = null
        ) {
            val isOn = forcedState ?: PrivilegedToggleHelper.isMobileDataEnabledBestEffort(context)
            val views = RemoteViews(context.packageName, R.layout.widget_data_toggle)
            views.setInt(
                R.id.widget_data_root,
                "setBackgroundResource",
                if (isOn) R.drawable.bg_widget_data_tile else R.drawable.bg_widget_data_tile_off
            )
            views.setInt(
                R.id.widget_data_icon,
                "setColorFilter",
                Color.parseColor(if (isOn) "#00E5FF" else "#64748B")
            )
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

        fun refreshAllWidgets(context: Context, forcedState: Boolean? = null) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, DataToggleWidget::class.java))
            }.getOrNull() ?: return
            for (widgetId in ids) {
                updateWidgetView(context, manager, widgetId, forcedState)
            }
        }

        fun handleWidgetTap(context: Context) {
            val toggledState = PrivilegedToggleHelper.toggleMobileDataSilently(context)
            if (toggledState != null) {
                refreshAllWidgets(context, forcedState = toggledState)
                Toast.makeText(
                    context,
                    "Mobile Data ${if (toggledState) "ON" else "OFF"}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val a11yService = SmartDotAccessibilityService.instance
            if (a11yService != null && !SmartDotAccessibilityService.isUserPaused) {
                a11yService.performQuickTileToggle(QuickTileAutomation.QuickTileTarget.MOBILE_DATA) { toggled ->
                    refreshAllWidgets(context)
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
                return
            }

            runCatching {
                context.startActivity(
                    QuickTileAutomation.buildFallbackSettingsIntent(
                        QuickTileAutomation.QuickTileTarget.MOBILE_DATA
                    )
                )
            }
        }
    }
}
