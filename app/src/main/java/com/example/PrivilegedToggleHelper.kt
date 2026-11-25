package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Performs native Android hardware toggles directly when permitted by the OS, and returns null
 * when the OS requires SmartDot's built-in Accessibility Quick-Tile automation or Settings Panel:
 * - Wi-Fi: WifiManager.setWifiEnabled on Android < 10 (Q).
 * - Bluetooth: BluetoothAdapter.enable/disable on Android < 13 (Tiramisu).
 * - Auto-Rotate & Brightness: Direct ContentResolver via Settings.System.WRITE_SETTINGS.
 * - Mobile Data: Handled by built-in Accessibility Quick-Tile automation / Connectivity Panel.
 */
object PrivilegedToggleHelper {

    private const val TAG = "PrivilegedToggleHelper"

    internal var mobileDataStateTestHook: (() -> Boolean?)? = null

    fun toggleSilently(
        context: Context,
        target: QuickTileAutomation.QuickTileTarget
    ): Boolean? {
        return when (target) {
            QuickTileAutomation.QuickTileTarget.AUTO_ROTATE -> toggleAutoRotateSilently(context)
            QuickTileAutomation.QuickTileTarget.WIFI -> toggleWifiSilently(context)
            QuickTileAutomation.QuickTileTarget.BLUETOOTH -> toggleBluetoothSilently(context)
            QuickTileAutomation.QuickTileTarget.MOBILE_DATA -> toggleMobileDataSilently(context)
        }
    }

    fun toggleAutoRotateSilently(context: Context): Boolean? {
        if (!Settings.System.canWrite(context)) {
            promptWriteSettingsPermission(context)
            return null
        }
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            val next = if (current == 1) 0 else 1
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, next)
            next == 1
        }.getOrNull()
    }

    fun adjustBrightnessByStep(context: Context, delta: Int): Int? {
        if (!Settings.System.canWrite(context)) {
            promptWriteSettingsPermission(context)
            return null
        }
        return runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val updated = (current + delta).coerceIn(15, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, updated)
            updated
        }.getOrNull()
    }

    fun promptWriteSettingsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(
                context,
                context.getString(R.string.write_settings_required),
                Toast.LENGTH_SHORT
            ).show()
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun toggleWifiSilently(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return null
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val nextState = !wifi.isWifiEnabled
        return runCatching {
            if (wifi.setWifiEnabled(nextState)) nextState else null
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun toggleBluetoothSilently(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        val adapter = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } else {
                BluetoothAdapter.getDefaultAdapter()
            }
        }.getOrNull() ?: return null

        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!hasBtPermission) return null

        val currentlyOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        val ok = runCatching {
            if (currentlyOn) adapter.disable() else adapter.enable()
        }.getOrDefault(false)
        return if (ok) !currentlyOn else null
    }

    fun toggleMobileDataSilently(@Suppress("UNUSED_PARAMETER") context: Context): Boolean? {
        // Stock unprivileged Android restricts programmatic mobile data toggling; handled via
        // SmartDot's built-in Accessibility Quick-Tile automation or Connectivity Settings Panel.
        return null
    }

    internal fun isMobileDataEnabledBestEffort(context: Context): Boolean {
        mobileDataStateTestHook?.invoke()?.let { return it }

        val settingFlag = runCatching {
            Settings.Global.getInt(context.contentResolver, "mobile_data", -1)
        }.getOrDefault(-1)
        if (settingFlag == 0 || settingFlag == 1) {
            return settingFlag == 1
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val tmState = runCatching { tm.isDataEnabled }.getOrNull()
            if (tmState != null) return tmState
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return true
            }
        }
        return false
    }

    /**
     * Dispatches Accessibility Power Dialog safely (for Power Menu shortcut).
     */
    fun triggerPowerMenu(service: SmartDotAccessibilityService?): Boolean {
        if (service == null) return false
        return runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        }.getOrElse { e ->
            Log.w(TAG, "Power dialog action failed", e)
            false
        }
    }
}
