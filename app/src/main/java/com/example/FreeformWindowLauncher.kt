package com.example

import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.URLEncoder

/**
 * Resolves full-screen app launches, SmartDot Floating Window package mappings,
 * and Realme Smart Sidebar triggers without relying on broken OS Freeform mode.
 */
object FreeformWindowLauncher {

    private const val TAG = "FreeformLauncher"

    sealed class FloatingAppTarget {
        data class SocialWeb(val appId: String) : FloatingAppTarget()
        data class Browser(val url: String) : FloatingAppTarget()
        data class AiWeb(val aiModeId: String) : FloatingAppTarget()
        object YouTube : FloatingAppTarget()
        object Notes : FloatingAppTarget()
        object Calculator : FloatingAppTarget()
        object MediaFiles : FloatingAppTarget()
    }

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
     * Resolves a web URL fallback / mapping for popular apps.
     */
    fun webFallbackUrlForPackage(packageName: String): String? {
        return when (packageName) {
            "com.google.android.youtube",
            "com.vanced.android.youtube",
            "app.revanced.android.youtube" -> "https://m.youtube.com"
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.google.android.googlequicksearchbox" -> "https://www.google.com"
            "com.google.android.apps.bard" -> "https://gemini.google.com/app"
            "com.openai.chatgpt" -> "https://chatgpt.com/"
            "com.whatsapp",
            "com.whatsapp.w4b" -> "https://web.whatsapp.com/"
            "com.instagram.android",
            "com.instagram.lite" -> "https://www.instagram.com/"
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca" -> "https://m.facebook.com/"
            "org.telegram.messenger",
            "org.telegram.messenger.web" -> "https://web.telegram.org/"
            "com.google.android.gm" -> "https://mail.google.com/"
            else -> null
        }
    }

    /**
     * Maps any active foreground Android package into SmartDot's own resizable Floating Window
     * equivalent, bypassing broken Android/Realme system Freeform windowing.
     */
    fun resolveFloatingTargetForPackage(
        packageName: String,
        appLabel: String? = null,
        detectedBrowserUrl: String? = null
    ): FloatingAppTarget {
        val pkg = packageName.trim()
        return when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> FloatingAppTarget.SocialWeb("whatsapp")
            "com.instagram.android", "com.instagram.lite" -> FloatingAppTarget.SocialWeb("instagram")
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca" -> FloatingAppTarget.SocialWeb("facebook")
            "org.telegram.messenger", "org.telegram.messenger.web" -> FloatingAppTarget.SocialWeb("telegram")
            "com.google.android.gm" -> FloatingAppTarget.SocialWeb("gmail")
            "com.google.android.youtube",
            "com.vanced.android.youtube",
            "app.revanced.android.youtube" -> FloatingAppTarget.YouTube
            "com.google.android.apps.bard" -> FloatingAppTarget.AiWeb("gemini")
            "com.openai.chatgpt" -> FloatingAppTarget.AiWeb("chatgpt")
            "com.google.android.keep",
            "com.google.android.apps.notes",
            "com.coloros.note" -> FloatingAppTarget.Notes
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.coloros.calculator",
            "com.oplus.calculator" -> FloatingAppTarget.Calculator
            "com.google.android.apps.photos",
            "com.google.android.apps.nbu.files",
            "com.coloros.gallery3d",
            "com.coloros.filemanager" -> FloatingAppTarget.MediaFiles
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser" -> {
                val url = detectedBrowserUrl?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
                FloatingAppTarget.Browser(url)
            }
            "com.google.android.googlequicksearchbox" -> {
                val url = detectedBrowserUrl?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
                FloatingAppTarget.Browser(url)
            }
            else -> {
                val cleanLabel = appLabel?.trim().takeUnless { it.isNullOrBlank() } ?: pkg.substringAfterLast('.')
                val encoded = runCatching { URLEncoder.encode(cleanLabel, "UTF-8") }.getOrDefault(cleanLabel)
                FloatingAppTarget.Browser("https://www.google.com/search?q=$encoded")
            }
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
