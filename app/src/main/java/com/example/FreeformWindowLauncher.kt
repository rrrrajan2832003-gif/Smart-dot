package com.example

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resolves full-screen app launches, web fallback URLs, and Realme Smart Sidebar triggers
 * without showing any disruptive Freeform or Developer Options dialogs.
 */
object FreeformWindowLauncher {

    private const val TAG = "FreeformLauncher"

    fun launchFullscreen(
        context: Context,
        rawIntent: Intent,
        appLabel: String? = null
    ): Boolean {
        val intent = Intent(rawIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching ${appLabel ?: "app"}", e)
            false
        }
    }

    /**
     * Resolves a web URL fallback for popular apps.
     */
    fun webFallbackUrlForPackage(packageName: String): String? {
        return when (packageName) {
            "com.google.android.youtube" -> "https://m.youtube.com"
            "com.android.chrome", "com.google.android.googlequicksearchbox" -> "https://www.google.com"
            "com.google.android.apps.bard" -> "https://gemini.google.com/app"
            "com.openai.chatgpt" -> "https://chatgpt.com/"
            "com.whatsapp" -> "https://web.whatsapp.com/"
            "com.google.android.gm" -> "https://mail.google.com/"
            else -> null
        }
    }

    /**
     * Attempts to invoke Realme UI / ColorOS Smart Sidebar (`com.oplus.smartsidebar` or
     * `com.coloros.smartsidebar`) if installed and enabled on the device.
     */
    fun launchRealmeSmartSidebar(context: Context): Boolean {
        val sidebarPackages = listOf("com.oplus.smartsidebar", "com.coloros.smartsidebar")
        val pm = context.packageManager

        val actionCandidates = listOf(
            "com.oplus.smartsidebar.ACTION_SHOW_SIDEBAR",
            "com.coloros.smartsidebar.ACTION_SHOW_SIDEBAR",
            "oplus.intent.action.SMART_SIDEBAR",
            "coloros.intent.action.SMART_SIDEBAR"
        )

        for (pkg in sidebarPackages) {
            for (act in actionCandidates) {
                val actIntent = Intent(act).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (actIntent.resolveActivity(pm) != null) {
                    val started = runCatching { context.startActivity(actIntent); true }.getOrDefault(false)
                    if (started) return true
                }
                if (pm.resolveService(actIntent, 0) != null) {
                    val svcStarted = runCatching { context.startService(actIntent) != null }.getOrDefault(false)
                    if (svcStarted) return true
                }
            }

            val launchIntent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
                if (ok) return true
            }
        }
        return false
    }
}
