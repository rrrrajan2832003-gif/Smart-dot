package com.example

/**
 * Unified Shortcut Catalog shared by MainActivity and FloatingService.
 * Note: Keeps "screen_translate" directly below "screen_copy" for ScreenTextCopyTest parity,
 * and includes all 20+ Floating Mini-Apps, AI, Media, Hardware, and In-App Shortcuts.
 */
data class AvailableAction(
    val id: String,
    val name: String,
    val category: String,
    val description: String
)

val ACTION_LIST = listOf(
    // Floating Mini-Apps (True Multi-Window Overlays)
    AvailableAction("floating_browser", "Floating Browser", "Floating Mini-Apps", "Resizable multi-tab floating web browser with Google Search"),
    AvailableAction("floating_youtube", "Floating YouTube", "Floating Mini-Apps", "Movable YouTube player with Desktop toggle & background audio"),
    AvailableAction("floating_notes", "Floating Notes", "Floating Mini-Apps", "Sticky notepad backed by local Room database"),
    AvailableAction("floating_calculator", "Floating Calculator", "Floating Mini-Apps", "Resizable calculator window with live formula evaluation"),
    AvailableAction("floating_translator", "Floating Translator", "Floating Mini-Apps", "Auto-detect offline ML Kit translator in a floating window"),
    AvailableAction("floating_viewer", "Floating File/Image Viewer", "Floating Mini-Apps", "Zero-permission Photo Picker & document viewer window"),

    // System Actions
    AvailableAction("home", "Home", "System Shortcuts", "Go to device Home screen"),
    AvailableAction("back", "Back", "System Shortcuts", "Go back to previous screen"),
    AvailableAction("recent_apps", "Recent Apps", "System Shortcuts", "Open system Recents screen"),
    AvailableAction("lock_screen", "Lock Screen", "System Shortcuts", "Lock the device screen immediately"),
    AvailableAction("power_menu", "Power Menu", "System Shortcuts", "Open Android system power & restart dialog"),
    AvailableAction("screenshot", "Screenshot", "System Shortcuts", "Capture system screen shot"),
    AvailableAction("screen_copy", "Screen Text Copy", "System Shortcuts", "Tap any visible text on screen to copy instantly"),
    AvailableAction("screen_translate", "Screen Translate", "System Shortcuts", "Tap any text on screen for instant offline translation"),
    AvailableAction("screen_record", "Screen Record", "System Shortcuts", "Record screen video to Movies/SmartDot"),
    AvailableAction("ask_ai", "AI Assistant (Ask AI)", "System Shortcuts", "Open Ask AI & AI Screen Summary panel"),
    AvailableAction("google_ai_mode", "Google AI Mode", "System Shortcuts", "Launch Google AI Mode directly in Google App"),
    AvailableAction("gemini", "Gemini AI", "System Shortcuts", "Launch Google Gemini assistant directly"),
    AvailableAction("chatgpt", "ChatGPT", "System Shortcuts", "Launch OpenAI ChatGPT app or web directly"),
    AvailableAction("notifications", "Notifications", "System Shortcuts", "Expand status bar notification panel"),
    AvailableAction("quick_settings", "Quick Settings", "System Shortcuts", "Expand status bar quick settings panel"),
    AvailableAction("split_screen", "Split Screen", "System Shortcuts", "Initiate direct 1-tap Split Screen Mode"),
    AvailableAction("floating_window", "Floating Window", "System Shortcuts", "Pop out current active app into a movable floating window"),
    AvailableAction("realme_smart_sidebar", "Realme Smart Sidebar", "System Shortcuts", "Trigger built-in Realme / ColorOS Smart Sidebar"),
    AvailableAction("open_app_drawer", "Open App Drawer", "System Shortcuts", "Open installed launcher app drawer"),
    AvailableAction("voice_assistant", "Voice Assistant", "System Shortcuts", "Activate Google Voice Assistant"),
    AvailableAction("about_app", "About App", "System Shortcuts", "Open local Floating Shortcuts About and Developer suite"),

    // Device & Media Controls
    AvailableAction("flashlight", "Flashlight", "Quick Preferences", "Toggle camera LED torch flashlight"),
    AvailableAction("wifi", "WiFi Settings", "Quick Preferences", "Silent instant Wi-Fi toggle or Settings Panel"),
    AvailableAction("mobile_data", "Mobile Data settings", "Quick Preferences", "Silent Mobile Data toggle or Connectivity Panel"),
    AvailableAction("bluetooth", "Bluetooth settings", "Quick Preferences", "Silent Bluetooth toggle or Bluetooth settings"),
    AvailableAction("auto_rotate", "Auto Rotation Toggle", "Quick Preferences", "Direct 1-tap screen auto-rotate toggle"),
    AvailableAction("brightness", "Brightness Booster", "Quick Preferences", "Open or step display brightness"),
    AvailableAction("brightness_up", "Brightness Up", "Quick Preferences", "Increase screen brightness silently"),
    AvailableAction("brightness_down", "Brightness Down", "Quick Preferences", "Decrease screen brightness silently"),
    AvailableAction("volume_panel", "Volume Panel", "Quick Preferences", "Show Android interactive volume slider popup"),
    AvailableAction("volume", "Volume Controller", "Quick Preferences", "Display system music and ringer sliders"),
    AvailableAction("media_play_pause", "Media Play/Pause", "Quick Preferences", "Play or pause active music/video playback"),
    AvailableAction("media_next", "Next Track", "Quick Preferences", "Skip to next audio/video track"),
    AvailableAction("mute", "Quiet Ringer (Mute)", "Quick Preferences", "Toggle ringtone silencing"),
    AvailableAction("dnd_mode", "Do Not Disturb (DND)", "Quick Preferences", "Toggle system interruption blocker"),
    AvailableAction("dark_mode", "Dark Theme Settings", "Quick Preferences", "Open Display color styles"),
    AvailableAction("airplane_mode", "Airplane Settings", "Quick Preferences", "Open wireless network preferences"),
    AvailableAction("hotspot", "Tether Settings (Hotspot)", "Quick Preferences", "Toggle internet sharing hotspot"),

    // Applications & Utilities
    AvailableAction("qr_scanner", "QR Scanner", "Utility Apps", "Open Google Lens / QR code scanner"),
    AvailableAction("camera", "System Camera", "Utility Apps", "Launch default camera viewfinder"),
    AvailableAction("gallery", "System Gallery", "Utility Apps", "Access saved photos and pictures"),
    AvailableAction("calculator", "System Calculator", "Utility Apps", "Open default calculator application"),
    AvailableAction("chrome", "Chrome Browser", "Utility Apps", "Open google chrome search engine"),
    AvailableAction("whatsapp", "WhatsApp Messenger", "Utility Apps", "Open Whatsapp quick chats"),
    AvailableAction("youtube", "YouTube App", "Utility Apps", "Open Youtube video sharing layout"),
    AvailableAction("gmail", "Gmail Inbox", "Utility Apps", "Launch gmail inbox editor"),
    AvailableAction("files", "Files Explorer", "Utility Apps", "Look up local downloads or documents"),
    AvailableAction("settings", "System Settings", "Utility Apps", "Open Android device preferences"),
    AvailableAction("clock", "Alarms and Clock", "Utility Apps", "Open deskclock alarms scheduler"),
    AvailableAction("contacts", "Contacts Manager", "Utility Apps", "Search for telephone directory people"),
    AvailableAction("messages", "Messages SMS", "Utility Apps", "Trigger default messaging SMS interface"),
    AvailableAction("custom_app", "Custom App Launcher", "Utility Apps", "Launch a custom Android package suffix"),

    // In-App Deep-Link Shortcuts
    AvailableAction("chrome_incognito", "Chrome Incognito", "In-App Shortcuts", "Open new Chrome Incognito tab directly"),
    AvailableAction("chrome_search", "Chrome Search", "In-App Shortcuts", "Start a fresh Google search in Chrome"),
    AvailableAction("youtube_subscriptions", "YouTube Subscriptions", "In-App Shortcuts", "Jump straight to YouTube Subscriptions feed"),
    AvailableAction("youtube_search", "YouTube Search", "In-App Shortcuts", "Open YouTube search directly"),
    AvailableAction("whatsapp_chat", "WhatsApp Direct Chat", "In-App Shortcuts", "Jump straight to starting a WhatsApp chat"),
    AvailableAction("whatsapp_camera", "WhatsApp Camera", "In-App Shortcuts", "Open WhatsApp camera capture directly"),
    AvailableAction("settings_wifi", "Settings: Wi-Fi", "In-App Shortcuts", "Deep link directly to Wi-Fi Settings"),
    AvailableAction("settings_bluetooth", "Settings: Bluetooth", "In-App Shortcuts", "Deep link directly to Bluetooth Settings"),
    AvailableAction("settings_battery", "Settings: Battery", "In-App Shortcuts", "Deep link directly to Battery usage Settings"),
    AvailableAction("settings_app_info", "Settings: App Info", "In-App Shortcuts", "Open App Info for active app or SmartDot"),
    AvailableAction("custom_url_intent", "Custom URL / Intent", "In-App Shortcuts", "Launch configured Custom URL or Intent URI")
)

fun resolveShortcutShortLabel(actionId: String): String {
    return when (actionId) {
        "floating_browser" -> "Browser"
        "floating_youtube" -> "YT Float"
        "floating_notes" -> "Notes Win"
        "floating_calculator" -> "Calc Win"
        "floating_translator" -> "Trans Win"
        "floating_viewer" -> "Viewer"
        "google_ai_mode" -> "AI Mode"
        "gemini" -> "Gemini"
        "chatgpt" -> "ChatGPT"
        "power_menu" -> "Power"
        "volume_panel" -> "Vol Panel"
        "media_play_pause" -> "Play/Pause"
        "media_next" -> "Next"
        "brightness_up" -> "Bright +"
        "brightness_down" -> "Bright -"
        "qr_scanner" -> "QR Scan"
        "home" -> "Home"
        "back" -> "Back"
        "recent_apps" -> "Recents"
        "lock_screen" -> "Lock"
        "screenshot" -> "Screenshot"
        "screen_copy" -> "Text Copy"
        "screen_translate" -> "Translate"
        "screen_record" -> "Screen Rec"
        "ask_ai" -> "Ask AI"
        "notifications" -> "Notif"
        "quick_settings" -> "Quick Set"
        "split_screen" -> "Split"
        "floating_window" -> "Float App"
        "open_menu" -> "Open Menu"
        "realme_smart_sidebar" -> "Smart Sidebar"
        "open_app_drawer" -> "Drawer"
        "voice_assistant" -> "Assistant"
        "about_app" -> "About"
        "flashlight" -> "Torch"
        "wifi" -> "WiFi"
        "mobile_data" -> "Mobile Data"
        "bluetooth" -> "Bluetooth"
        "brightness" -> "Brightness"
        "volume" -> "Volume"
        "mute" -> "Mute"
        "dnd_mode" -> "DND"
        "auto_rotate" -> "Rotation"
        "dark_mode" -> "Theme"
        "airplane_mode" -> "Airplane"
        "hotspot" -> "Hotspot"
        "camera" -> "Camera"
        "gallery" -> "Gallery"
        "calculator" -> "Calc"
        "translate" -> "Translate"
        "clipboard" -> "Clipboard"
        "notes" -> "Notes"
        "chrome" -> "Chrome"
        "whatsapp" -> "WhatsApp"
        "youtube" -> "YouTube"
        "gmail" -> "Gmail"
        "files" -> "Files"
        "settings" -> "Dash"
        "clock" -> "Clock"
        "contacts" -> "Contacts"
        "messages" -> "Messages"
        "custom_app" -> "Custom"
        "chrome_incognito" -> "Incognito"
        "chrome_search" -> "Web Search"
        "youtube_subscriptions" -> "YT Subs"
        "youtube_search" -> "YT Search"
        "whatsapp_chat" -> "WA Chat"
        "whatsapp_camera" -> "WA Cam"
        "settings_wifi" -> "Wi-Fi Pref"
        "settings_bluetooth" -> "BT Pref"
        "settings_battery" -> "Battery"
        "settings_app_info" -> "App Info"
        "custom_url_intent" -> "Custom URL"
        else -> "Shortcut"
    }
}
