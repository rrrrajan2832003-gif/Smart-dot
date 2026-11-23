package com.example

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast

/**
 * Performs silent instant hardware toggles completely in the background without pulling down
 * the system Quick Settings shade (GLOBAL_ACTION_QUICK_SETTINGS):
 * - Auto-Rotate & Brightness: Direct ContentResolver (Settings.System.WRITE_SETTINGS).
 * - Mobile Data, Wi-Fi, Bluetooth: Direct Android APIs, Shizuku privileged IPC, or `su -c svc ...`
 *   root commands; falls back cleanly to Android Settings Panels if unprivileged.
 */
object PrivilegedToggleHelper {

    private const val TAG = "PrivilegedToggleHelper"

    internal var shizukuShellTestHook: ((String) -> Boolean?)? = null
    internal var rootShellTestHook: ((String) -> Boolean?)? = null
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
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val currentlyOn = wifi?.isWifiEnabled == true
        val nextState = !currentlyOn

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifi != null) {
            val directOk = runCatching { wifi.setWifiEnabled(nextState) }.getOrDefault(false)
            if (directOk) return nextState
        }

        val cmd = if (nextState) "svc wifi enable" else "svc wifi disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }
        return null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun toggleBluetoothSilently(context: Context): Boolean? {
        val adapter = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } else {
                BluetoothAdapter.getDefaultAdapter()
            }
        }.getOrNull()

        val currentlyOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val nextState = !currentlyOn

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null) {
            val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            if (hasBtPermission) {
                val directOk = runCatching {
                    if (currentlyOn) adapter.disable() else adapter.enable()
                }.getOrDefault(false)
                if (directOk) return nextState
            }
        }

        val cmd = if (nextState) "svc bluetooth enable" else "svc bluetooth disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }
        return null
    }

    fun toggleMobileDataSilently(context: Context): Boolean? {
        val currentlyOn = isMobileDataEnabledBestEffort(context)
        val nextState = !currentlyOn

        // 1) Try Settings.Global.putInt if WRITE_SECURE_SETTINGS was granted (via ADB/root)
        val hasWriteSecure = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasWriteSecure) {
            val globalOk = runCatching {
                Settings.Global.putInt(context.contentResolver, "mobile_data", if (nextState) 1 else 0)
            }.getOrDefault(false)
            if (globalOk) return nextState
        }

        // 2) Attempt direct TelephonyManager.setDataEnabled only on system/carrier-privileged apps
        val hasModifyPhoneState = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            "android.permission.MODIFY_PHONE_STATE"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val telephonyOk = if (hasModifyPhoneState) {
            runCatching {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    ?: return@runCatching false
                val setEnabled = TelephonyManager::class.java.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                setEnabled.invoke(tm, nextState)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (telephonyOk) return nextState

        // 3) Shizuku / root shell command (`svc data enable/disable`)
        val cmd = if (nextState) "svc data enable" else "svc data disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }
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

    internal fun executePrivilegedShell(command: String): Boolean {
        shizukuShellTestHook?.invoke(command)?.let { return it }
        if (tryExecuteViaShizuku(command)) {
            return true
        }
        rootShellTestHook?.invoke(command)?.let { return it }
        return tryExecuteViaRoot(command)
    }

    private fun tryExecuteViaShizuku(command: String): Boolean {
        return runCatching {
            val shizukuCls = Class.forName("rikka.shizuku.Shizuku")
            val pingBinder = shizukuCls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!pingBinder) return@runCatching false
            val checkSelfPermission = shizukuCls.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1
            if (checkSelfPermission != 0) return@runCatching false
            val newProcess = shizukuCls.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val proc = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return@runCatching false
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun tryExecuteViaRoot(command: String): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            runCatching { process.destroy() }
            exitCode == 0
        }.getOrDefault(false)
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
