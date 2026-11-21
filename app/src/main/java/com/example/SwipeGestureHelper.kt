package com.example

import android.content.SharedPreferences
import kotlin.math.abs

/**
 * Shared directional swipe gesture configuration & classifier for both the
 * Floating Dot and the Edge Sidebar (Associative Swipe style).
 */
object SwipeGestureHelper {

    enum class SwipeDirection(
        val prefKey: String,
        val label: String,
        val description: String
    ) {
        SWIPE_UP(
            prefKey = "gesture_swipe_up",
            label = "Swipe Up",
            description = "Flick straight upward from the handle or dot"
        ),
        SWIPE_DOWN(
            prefKey = "gesture_swipe_down",
            label = "Swipe Down",
            description = "Flick straight downward from the handle or dot"
        ),
        SWIPE_LEFT(
            prefKey = "gesture_swipe_left",
            label = "Swipe Left / Inward",
            description = "Flick inward across the screen edge (or left from the dot)"
        ),
        SWIPE_UP_LEFT(
            prefKey = "gesture_swipe_up_left",
            label = "Swipe Up-Left (Diagonal)",
            description = "Flick diagonally upward and inward"
        ),
        SWIPE_DOWN_LEFT(
            prefKey = "gesture_swipe_down_left",
            label = "Swipe Down-Left (Diagonal)",
            description = "Flick diagonally downward and inward"
        )
    }

    data class GestureActionOption(
        val id: String,
        val name: String
    )

    val GESTURE_ACTIONS: List<GestureActionOption> = listOf(
        GestureActionOption("none", "None"),
        GestureActionOption("open_menu", "Open Floating Menu"),
        GestureActionOption("back", "Back"),
        GestureActionOption("home", "Home"),
        GestureActionOption("recent_apps", "Recents"),
        GestureActionOption("lock_screen", "Lock Screen"),
        GestureActionOption("floating_window", "Floating Window"),
        GestureActionOption("realme_smart_sidebar", "Realme Smart Sidebar"),
        GestureActionOption("split_screen", "Split Screen"),
        GestureActionOption("screenshot", "Screenshot"),
        GestureActionOption("notifications", "Notification Panel"),
        GestureActionOption("quick_settings", "Quick Settings"),
        GestureActionOption("flashlight", "Flashlight"),
        GestureActionOption("wifi", "Wi-Fi Toggle"),
        GestureActionOption("mobile_data", "Mobile Data Toggle"),
        GestureActionOption("bluetooth", "Bluetooth Toggle"),
        GestureActionOption("auto_rotate", "Auto-Rotate Toggle"),
        GestureActionOption("screen_copy", "Screen Text Copy"),
        GestureActionOption("screen_translate", "Screen Translate"),
        GestureActionOption("screen_record", "Screen Recording"),
        GestureActionOption("ask_ai", "AI Assistant (Ask AI)"),
        GestureActionOption("voice_assistant", "Voice Assistant"),
        GestureActionOption("chrome_incognito", "Chrome Incognito"),
        GestureActionOption("chrome_search", "Chrome Search"),
        GestureActionOption("youtube_subscriptions", "YouTube Subscriptions"),
        GestureActionOption("youtube_search", "YouTube Search"),
        GestureActionOption("whatsapp_chat", "WhatsApp Direct Chat"),
        GestureActionOption("whatsapp_camera", "WhatsApp Camera"),
        GestureActionOption("settings_wifi", "Settings: Wi-Fi"),
        GestureActionOption("settings_bluetooth", "Settings: Bluetooth"),
        GestureActionOption("settings_battery", "Settings: Battery"),
        GestureActionOption("settings_app_info", "Settings: App Info"),
        GestureActionOption("custom_url_intent", "Custom URL / Intent")
    )

    fun getActionDisplayName(actionId: String): String {
        return GESTURE_ACTIONS.firstOrNull { it.id == actionId }?.name ?: "None"
    }

    fun getAssignedAction(prefs: SharedPreferences, direction: SwipeDirection): String {
        return prefs.getString(direction.prefKey, "none") ?: "none"
    }

    fun setAssignedAction(prefs: SharedPreferences, direction: SwipeDirection, actionId: String) {
        prefs.edit().putString(direction.prefKey, actionId).apply()
    }

    /**
     * Classifies a pointer drag/flick delta `(dx, dy)` into one of the 5 supported
     * directional swipe gestures.
     *
     * @param dx horizontal displacement in pixels (positive = right, negative = left)
     * @param dy vertical displacement in pixels (positive = down, negative = up)
     * @param velocityPxPerSec resultant gesture speed in px/sec (from VelocityTracker or displacement/duration)
     * @param minDeltaPx minimum distance in pixels before any swipe is recognized
     * @param fastFlickVelocityPxPerSec high-speed flick threshold allowing slightly shorter strokes
     * @param inwardIsRight true when attached to the LEFT screen edge so inward swipes move right (+dx)
     */
    fun detectSwipeDirection(
        dx: Float,
        dy: Float,
        velocityPxPerSec: Float = 0f,
        minDeltaPx: Float,
        fastFlickVelocityPxPerSec: Float = minDeltaPx * 8f,
        inwardIsRight: Boolean = false
    ): SwipeDirection? {
        val absDx = abs(dx)
        val absDy = abs(dy)
        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        val meetsDistance = distance >= minDeltaPx
        val meetsFastFlick = velocityPxPerSec >= fastFlickVelocityPxPerSec && distance >= (minDeltaPx * 0.55f)
        if (!meetsDistance && !meetsFastFlick) {
            return null
        }

        // Normalize inward direction so "Left / Inward" works consistently on Right edge, Dot, and Left edge
        val inwardDx = if (inwardIsRight) dx else -dx

        // Check diagonals first when both inward and vertical components are clearly present (~22 deg to 68 deg)
        if (inwardDx > 0f && absDy > 0f) {
            val slopeRatio = absDy / inwardDx
            if (slopeRatio in 0.40f..2.45f) {
                return if (dy < 0f) {
                    SwipeDirection.SWIPE_UP_LEFT
                } else {
                    SwipeDirection.SWIPE_DOWN_LEFT
                }
            }
        }

        // Otherwise classify into cardinal directions
        return when {
            absDy >= absDx && dy < 0f -> SwipeDirection.SWIPE_UP
            absDy >= absDx && dy > 0f -> SwipeDirection.SWIPE_DOWN
            inwardDx > 0f -> SwipeDirection.SWIPE_LEFT
            else -> null
        }
    }
}
