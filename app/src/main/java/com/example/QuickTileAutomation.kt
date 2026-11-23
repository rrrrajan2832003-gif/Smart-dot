package com.example

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Provides 1-tap toggles for Wi-Fi, Mobile Data, Bluetooth, and Auto-Rotate:
 * 1) Attempts direct system API toggle when supported on the OS version / permission level.
 * 2) Otherwise uses SmartDotAccessibilityService to open Quick Settings, find and click the
 *    matching Quick Settings tile (or Android 12+ Internet panel switch) automatically, and
 *    press Back right after to return seamlessly to the user's app.
 * 3) Falls back cleanly to Android Settings Panel / Settings page if Accessibility is disabled
 *    or an OEM blocks Quick Settings inspection.
 */
object QuickTileAutomation {

    private const val TAG = "QuickTileAutomation"

    enum class QuickTileTarget(
        val displayName: String,
        val primaryKeywords: List<String>,
        val subPanelKeywords: List<String>
    ) {
        WIFI(
            displayName = "Wi-Fi",
            primaryKeywords = listOf("wi-fi", "wifi", "wlan", "internet"),
            subPanelKeywords = listOf("wi-fi", "wifi", "wlan")
        ),
        MOBILE_DATA(
            displayName = "Mobile Data",
            primaryKeywords = listOf("mobile data", "cellular data", "sim data", "5g", "4g", "internet"),
            subPanelKeywords = listOf("mobile data", "cellular", "mobile network")
        ),
        BLUETOOTH(
            displayName = "Bluetooth",
            primaryKeywords = listOf("bluetooth"),
            subPanelKeywords = listOf("use bluetooth", "bluetooth")
        ),
        AUTO_ROTATE(
            displayName = "Auto-Rotate",
            primaryKeywords = listOf("auto-rotate", "auto rotate", "auto-rotation", "screen rotation", "portrait"),
            subPanelKeywords = listOf("auto-rotate", "auto rotate")
        )
    }

    data class TileMatch(
        val clickableNode: AccessibilityNodeInfo,
        val matchedLabel: String
    )

    @Suppress("DEPRECATION")
    fun tryDirectApiToggle(context: Context, target: QuickTileTarget): Boolean? {
        return when (target) {
            QuickTileTarget.WIFI -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    runCatching {
                        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                            ?: return@runCatching null
                        val nextState = !wifi.isWifiEnabled
                        if (wifi.setWifiEnabled(nextState)) nextState else null
                    }.getOrNull()
                } else {
                    null
                }
            }

            QuickTileTarget.BLUETOOTH -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    @SuppressLint("MissingPermission")
                    runCatching {
                        val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        } else {
                            BluetoothAdapter.getDefaultAdapter()
                        } ?: return@runCatching null
                        val currentlyOn = adapter.isEnabled
                        val ok = if (currentlyOn) adapter.disable() else adapter.enable()
                        if (ok) !currentlyOn else null
                    }.getOrNull()
                } else {
                    null
                }
            }

            QuickTileTarget.AUTO_ROTATE -> {
                if (Settings.System.canWrite(context)) {
                    runCatching {
                        val resolver: ContentResolver = context.contentResolver
                        val current = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                        val next = if (current == 1) 0 else 1
                        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, next)
                        next == 1
                    }.getOrNull()
                } else {
                    null
                }
            }

            QuickTileTarget.MOBILE_DATA -> null
        }
    }

    fun buildFallbackSettingsIntent(target: QuickTileTarget): Intent {
        val action = when (target) {
            QuickTileTarget.WIFI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
            }
            QuickTileTarget.MOBILE_DATA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_DATA_ROAMING_SETTINGS
                }
            }
            QuickTileTarget.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            QuickTileTarget.AUTO_ROTATE -> Settings.ACTION_DISPLAY_SETTINGS
        }
        return Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun findMatchingTileInRoots(
        roots: List<AccessibilityNodeInfo>,
        keywords: List<String>
    ): TileMatch? {
        for (keyword in keywords) {
            for (root in roots) {
                val found = findNodeMatchingKeyword(root, keyword.lowercase(), depth = 0)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeMatchingKeyword(
        node: AccessibilityNodeInfo,
        keywordLower: String,
        depth: Int
    ): TileMatch? {
        if (depth > 32) return null

        val label = extractNodeLabel(node)
        if (label != null && matchesTileLabel(label, keywordLower)) {
            val clickable = resolveClickableContainer(node)
            if (clickable != null) {
                return TileMatch(clickable, label)
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val match = findNodeMatchingKeyword(child, keywordLower, depth + 1)
            if (match != null) return match
        }
        return null
    }

    private fun extractNodeLabel(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (t != null) return t
        return node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun matchesTileLabel(rawLabel: String, keywordLower: String): Boolean {
        val lower = rawLabel.trim().lowercase()
        if (lower == keywordLower) return true
        // Avoid matching descriptive sentences like "Turn off Wi-Fi hotspot" when looking for "wi-fi"
        if (lower.contains("hotspot") && !keywordLower.contains("hotspot")) return false
        if (lower.startsWith(keywordLower)) return true
        val words = lower.split(' ', ',', ':', '\n', '-', '/')
        return words.contains(keywordLower) || lower.contains(keywordLower)
    }

    private fun resolveClickableContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= 5) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
            hops++
        }
        return if (node.isEnabled) node else null
    }

    internal const val TILE_POLL_DELAY_MS = 80L
    internal const val BACK_COLLAPSE_DELAY_MS = 80L

    /**
     * Automates Quick Settings tile toggling on an active [SmartDotAccessibilityService] or
     * injected hooks for unit testing using 80ms fast-poll & instant shade collapse.
     */
    fun automateQuickSettingsToggle(
        target: QuickTileTarget,
        handler: Handler = Handler(Looper.getMainLooper()),
        performGlobalActionFn: (Int) -> Boolean,
        rootsProvider: () -> List<AccessibilityNodeInfo>,
        onResult: (toggled: Boolean) -> Unit
    ) {
        if (!performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            onResult(false)
            return
        }

        var pollAttempts = 0
        val maxAttempts = 10

        fun pollForTile() {
            val roots = rootsProvider()
            val match = findMatchingTileInRoots(roots, target.primaryKeywords)
            if (match != null) {
                val clicked = runCatching {
                    match.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)

                if (!clicked) {
                    performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_BACK)
                    onResult(false)
                    return
                }

                val isInternetSuperTile = match.matchedLabel.lowercase().contains("internet") &&
                    (target == QuickTileTarget.WIFI || target == QuickTileTarget.MOBILE_DATA)

                if (isInternetSuperTile) {
                    // Android 12+ Internet panel opens a bottom dialog containing Wi-Fi and Mobile Data switches
                    var subAttempts = 0
                    fun pollSubPanel() {
                        val subRoots = rootsProvider()
                        val subMatch = findMatchingTileInRoots(subRoots, target.subPanelKeywords)
                        if (subMatch != null && !subMatch.matchedLabel.lowercase().contains("internet")) {
                            runCatching { subMatch.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                            handler.postDelayed({
                                performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_BACK)
                                handler.postDelayed({
                                    performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_BACK)
                                    onResult(true)
                                }, BACK_COLLAPSE_DELAY_MS)
                            }, BACK_COLLAPSE_DELAY_MS)
                        } else if (++subAttempts < 7) {
                            handler.postDelayed(::pollSubPanel, TILE_POLL_DELAY_MS)
                        } else {
                            // Keep panel open if sub-toggle could not be isolated
                            onResult(true)
                        }
                    }
                    handler.postDelayed(::pollSubPanel, TILE_POLL_DELAY_MS)
                } else {
                    // Single tile click toggled directly -> collapse Quick Settings immediately
                    handler.postDelayed({
                        performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_BACK)
                        onResult(true)
                    }, BACK_COLLAPSE_DELAY_MS)
                }
                return
            }

            if (++pollAttempts < maxAttempts) {
                handler.postDelayed(::pollForTile, TILE_POLL_DELAY_MS)
            } else {
                Log.w(TAG, "Quick tile '${target.displayName}' not found after polling; closing shade.")
                performGlobalActionFn(AccessibilityService.GLOBAL_ACTION_BACK)
                onResult(false)
            }
        }

        handler.postDelayed(::pollForTile, TILE_POLL_DELAY_MS)
    }
}
