package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Auto-starts FloatingService on device boot (when Dot or Edge Sidebar is enabled)
 * and restarts the persistent service if scheduled by onTaskRemoved().
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isSupportedAction(action)) return

        val prefs = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
        val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
        if (!dotEnabled && !sidebarEnabled) return
        if (!Settings.canDrawOverlays(context)) return

        runCatching {
            val serviceIntent = Intent(context, FloatingService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        const val ACTION_RESTART_SERVICE = "com.example.ACTION_RESTART_SERVICE"

        fun isSupportedAction(action: String): Boolean {
            return action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == ACTION_RESTART_SERVICE
        }
    }
}
