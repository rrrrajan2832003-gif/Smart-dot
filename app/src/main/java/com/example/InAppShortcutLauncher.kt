package com.example

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * Handles In-App Shortcuts (deep links directly to Chrome Incognito/Search,
 * YouTube Subscriptions/Search, WhatsApp Chat/Camera, specific Settings panels,
 * and user-defined Custom URL / Intent URIs).
 */
object InAppShortcutLauncher {

    const val PREF_CUSTOM_INTENT_URI = "custom_intent_uri"

    fun isInAppShortcut(actionId: String): Boolean {
        return when (actionId) {
            "chrome_incognito",
            "chrome_search",
            "youtube_subscriptions",
            "youtube_search",
            "whatsapp_chat",
            "whatsapp_camera",
            "settings_wifi",
            "settings_bluetooth",
            "settings_battery",
            "settings_app_info",
            "custom_url_intent" -> true
            else -> false
        }
    }

    fun buildIntentCandidates(context: Context, actionId: String): List<Pair<Intent, String>> {
        val pm = context.packageManager
        val list = ArrayList<Pair<Intent, String>>()
        when (actionId) {
            "chrome_incognito" -> {
                list.add(
                    Intent("org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB").apply {
                        setPackage("com.android.chrome")
                    } to "Chrome Incognito"
                )
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("googlechrome://navigate?url=about:blank")).apply {
                        setPackage("com.android.chrome")
                        putExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB", true)
                    } to "Chrome Incognito"
                )
                pm.getLaunchIntentForPackage("com.android.chrome")?.let {
                    it.putExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB", true)
                    list.add(it to "Chrome")
                }
                list.add(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")) to "Chrome")
            }

            "chrome_search" -> {
                list.add(
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        setPackage("com.android.chrome")
                        putExtra(SearchManager.QUERY, "")
                    } to "Chrome Search"
                )
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/")).apply {
                        setPackage("com.android.chrome")
                    } to "Chrome Search"
                )
                list.add(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/")) to "Search")
            }

            "youtube_subscriptions" -> {
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/feed/subscriptions")).apply {
                        setPackage("com.google.android.youtube")
                    } to "YT Subscriptions"
                )
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/feed/subscriptions")) to "YT Subscriptions"
                )
            }

            "youtube_search" -> {
                list.add(
                    Intent("com.google.android.youtube.intent.action.SEARCH").apply {
                        setPackage("com.google.android.youtube")
                    } to "YT Search"
                )
                list.add(
                    Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra(SearchManager.QUERY, "")
                    } to "YT Search"
                )
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=")) to "YT Search"
                )
            }

            "whatsapp_chat" -> {
                list.add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send")).apply {
                        setPackage("com.whatsapp")
                    } to "WhatsApp Chat"
                )
                pm.getLaunchIntentForPackage("com.whatsapp")?.let {
                    list.add(it to "WhatsApp")
                }
                list.add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to "Chat")
            }

            "whatsapp_camera" -> {
                list.add(
                    Intent("com.whatsapp.intent.action.CAMERA").apply {
                        setPackage("com.whatsapp")
                    } to "WhatsApp Camera"
                )
                list.add(
                    Intent().apply {
                        setClassName("com.whatsapp", "com.whatsapp.camera.CameraActivity")
                    } to "WhatsApp Camera"
                )
                pm.getLaunchIntentForPackage("com.whatsapp")?.let {
                    list.add(it to "WhatsApp")
                }
            }

            "settings_wifi" -> {
                list.add(Intent(Settings.ACTION_WIFI_SETTINGS) to "Wi-Fi Settings")
            }

            "settings_bluetooth" -> {
                list.add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth Settings")
            }

            "settings_battery" -> {
                list.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY) to "Battery Settings")
                list.add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) to "Battery Settings")
            }

            "settings_app_info" -> {
                val activePkg = SmartDotAccessibilityService.instance
                    ?.getActiveForegroundPackageName(excludedPackage = context.packageName)
                    ?.takeIf { it.isNotBlank() }
                    ?: context.packageName
                list.add(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$activePkg")
                    } to "App Info"
                )
            }

            "custom_url_intent" -> {
                val rawUri = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
                    .getString(PREF_CUSTOM_INTENT_URI, "")?.trim().orEmpty()
                if (rawUri.isNotEmpty()) {
                    val parsedIntent = if (rawUri.startsWith("intent:", ignoreCase = true)) {
                        runCatching { Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME) }.getOrNull()
                    } else {
                        val normalized = if (rawUri.contains("://") || rawUri.contains(":")) rawUri else "https://$rawUri"
                        Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
                    }
                    if (parsedIntent != null) {
                        list.add(parsedIntent to "Custom Shortcut")
                    }
                }
            }
        }
        return list
    }

    fun launch(context: Context, actionId: String): Boolean {
        if (actionId == "custom_url_intent") {
            val rawUri = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
                .getString(PREF_CUSTOM_INTENT_URI, "")?.trim().orEmpty()
            if (rawUri.isEmpty()) {
                Toast.makeText(context, "Set a Custom URL / Intent URI in Settings first.", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        val candidates = buildIntentCandidates(context, actionId)
        for ((intent, _) in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        Toast.makeText(context, "Target app or shortcut unavailable.", Toast.LENGTH_SHORT).show()
        return false
    }
}
