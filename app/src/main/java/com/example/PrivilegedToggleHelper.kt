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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Performs silent 1-tap hardware toggles directly in the background without ever opening
 * Quick Settings or Android Settings panels for Mobile Data, Wi-Fi, or Bluetooth:
 * - Mobile Data: Shizuku (`svc data enable/disable`) -> WRITE_SECURE_SETTINGS + TelephonyManager reflection.
 * - Wi-Fi & Bluetooth: Direct Adapter/Manager API -> Shizuku (`svc wifi/bluetooth`) -> WRITE_SECURE_SETTINGS.
 * - Auto-Rotate & Brightness: Direct ContentResolver (Settings.System.WRITE_SETTINGS).
 */
object PrivilegedToggleHelper {

    private const val TAG = "PrivilegedToggleHelper"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    internal var shizukuShellTestHook: ((String) -> Boolean?)? = null
    internal var rootShellTestHook: ((String) -> Boolean?)? = null
    internal var mobileDataStateTestHook: (() -> Boolean?)? = null
    internal var shizukuActiveTestHook: (() -> Boolean)? = null

    fun adbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)
    }

    fun isShizukuActive(): Boolean {
        shizukuActiveTestHook?.let { return it() }
        return runCatching {
            val shizukuCls = Class.forName("rikka.shizuku.Shizuku")
            val pingBinder = shizukuCls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!pingBinder) return@runCatching false
            val checkSelfPermission = shizukuCls.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1
            checkSelfPermission == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

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
        val currentlyOn = when {
            wifi != null -> wifi.isWifiEnabled
            else -> Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1
        }
        val nextState = !currentlyOn

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifi != null) {
            val directOk = runCatching { wifi.setWifiEnabled(nextState) }.getOrDefault(false)
            if (directOk) return nextState
        }

        val cmd = if (nextState) "svc wifi enable" else "svc wifi disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }

        if (hasWriteSecureSettings(context)) {
            val globalOk = runCatching {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.WIFI_ON,
                    if (nextState) 1 else 0
                )
            }.getOrDefault(false)
            if (globalOk) return nextState
        }

        if (wifi != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Try direct setWifiEnabled on OEM ROMs where legacy target behavior allows it
            val oemOk = runCatching { wifi.setWifiEnabled(nextState) }.getOrDefault(false)
            if (oemOk) return nextState
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

        val currentlyOn = runCatching {
            adapter?.isEnabled
                ?: (Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 0) == 1)
        }.getOrDefault(false)
        val nextState = !currentlyOn

        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null && hasBtPermission) {
            val directOk = runCatching {
                if (currentlyOn) adapter.disable() else adapter.enable()
            }.getOrDefault(false)
            if (directOk) return nextState
        }

        val cmd = if (nextState) "svc bluetooth enable" else "svc bluetooth disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }

        if (hasWriteSecureSettings(context)) {
            val globalOk = runCatching {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.BLUETOOTH_ON,
                    if (nextState) 1 else 0
                )
            }.getOrDefault(false)
            if (globalOk) return nextState
        }

        if (adapter != null && hasBtPermission) {
            val fallbackDirect = runCatching {
                if (currentlyOn) adapter.disable() else adapter.enable()
            }.getOrDefault(false)
            if (fallbackDirect) return nextState
        }
        return null
    }

    fun toggleMobileDataSilently(context: Context): Boolean? {
        val currentlyOn = isMobileDataEnabledBestEffort(context)
        val nextState = !currentlyOn

        // 1) Shizuku / root shell (`svc data enable` / `svc data disable`) FIRST
        val cmd = if (nextState) "svc data enable" else "svc data disable"
        if (executePrivilegedShell(cmd)) {
            return nextState
        }

        // 2) WRITE_SECURE_SETTINGS + TelephonyManager reflection (`setDataEnabled`)
        val hasWriteSecure = hasWriteSecureSettings(context)
        val hasModifyPhoneState = ContextCompat.checkSelfPermission(
            context,
            "android.permission.MODIFY_PHONE_STATE"
        ) == PackageManager.PERMISSION_GRANTED

        var telephonyOk = false
        if (hasWriteSecure || hasModifyPhoneState) {
            telephonyOk = invokeTelephonySetDataEnabled(context, nextState)
        }

        val globalOk = if (hasWriteSecure) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, "mobile_data", if (nextState) 1 else 0)
            }.getOrDefault(false)
        } else {
            false
        }

        if (globalOk || telephonyOk) {
            return nextState
        }
        return null
    }

    private fun invokeTelephonySetDataEnabled(context: Context, enabled: Boolean): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        val subId = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } else {
                -1
            }
        }.getOrDefault(-1)

        if (subId > 0) {
            val subOk = runCatching {
                val method = TelephonyManager::class.java.getMethod(
                    "setDataEnabled",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(tm, subId, enabled)
                true
            }.getOrDefault(false)
            if (subOk) return true
        }

        return runCatching {
            val method = TelephonyManager::class.java.getMethod(
                "setDataEnabled",
                Boolean::class.javaPrimitiveType
            )
            method.invoke(tm, enabled)
            true
        }.getOrDefault(false)
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
