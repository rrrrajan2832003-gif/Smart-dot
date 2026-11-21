package com.example

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Launches external phone apps in Android Freeform Multi-Window / Realme Flexible Window mode
 * (`WINDOWING_MODE_FREEFORM = 5`) with Realme/Oplus native freeform extras + bounded Rect.
 * Also supports invoking Realme UI's built-in Smart Sidebar when present on device.
 */
object FreeformWindowLauncher {

    const val WINDOWING_MODE_FREEFORM = 5
    const val EXTRA_ANDROID_WINDOWING_MODE = "android.activity.windowingMode"
    const val EXTRA_OPLUS_FREEFORM = "com.oplus.freeform"
    val REALME_FLEXIBLE_BOUNDS = Rect(120, 250, 960, 1450)

    private const val PREF_FREEFORM_PROMPT_MUTED = "freeform_prompt_muted"
    private const val TAG = "FreeformLauncher"

    internal var activeGuideDialogRef: WeakReference<AlertDialog>? = null
    internal var promptShownCountForTest: Int = 0

    fun computeCenteredFreeformBounds(context: Context): Rect {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(360)
        val screenH = metrics.heightPixels.coerceAtLeast(480)

        // Use exact Realme Flexible Window default bounds when display accommodates them
        if (screenW >= 1000 && screenH >= 1600) {
            return Rect(REALME_FLEXIBLE_BOUNDS)
        }

        // Proportionally map the 120..960 x 250..1450 layout on smaller screens/test displays
        val density = metrics.density.coerceAtLeast(1f)
        val minW = (200 * density).toInt().coerceAtMost((screenW - 24).coerceAtLeast(140))
        val minH = (240 * density).toInt().coerceAtMost((screenH - 48).coerceAtLeast(180))
        val winW = (screenW * (840f / 1080f)).toInt().coerceIn(minW, (screenW - 24).coerceAtLeast(minW))
        val winH = (screenH * (1200f / 2400f)).toInt().coerceIn(minH, (screenH - 48).coerceAtLeast(minH))

        val left = ((screenW - winW) / 2).coerceAtLeast(12)
        val top = ((screenH - winH) / 2).coerceAtLeast(24)
        val right = (left + winW).coerceAtMost(screenW - 12)
        val bottom = (top + winH).coerceAtMost(screenH - 24)
        return Rect(left, top, right, bottom)
    }

    fun isFreeformMultiWindowEnabled(context: Context): Boolean {
        val hasFeature = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        }.getOrDefault(false)
        val devFreeformEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) == 1
        }.getOrDefault(false)
        return hasFeature || devFreeformEnabled
    }

    fun prepareFreeformIntent(rawIntent: Intent): Intent {
        return Intent(rawIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_ANDROID_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
            putExtra(EXTRA_OPLUS_FREEFORM, true)
        }
    }

    fun buildFreeformActivityOptions(context: Context): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(computeCenteredFreeformBounds(context))
        applyLaunchWindowingMode(options, WINDOWING_MODE_FREEFORM)
        return options
    }

    fun applyLaunchWindowingMode(options: ActivityOptions, mode: Int): Boolean {
        return runCatching {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            method.invoke(options, mode)
            true
        }.getOrElse { e ->
            Log.w(TAG, "setLaunchWindowingMode($mode) unavailable on this runtime", e)
            false
        }
    }

    fun launchInFreeformWindow(
        context: Context,
        rawIntent: Intent,
        appLabel: String? = null
    ): Boolean {
        val preparedIntent = prepareFreeformIntent(rawIntent)
        val options = buildFreeformActivityOptions(context)

        val launched = try {
            context.startActivity(preparedIntent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Freeform startActivity with options failed, falling back", e)
            try {
                context.startActivity(preparedIntent)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed launching ${appLabel ?: "app"}", fallbackEx)
                false
            }
        }

        if (launched && !isFreeformMultiWindowEnabled(context)) {
            showFreeformEnablePrompt(context)
        }
        return launched
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

    fun showFreeformEnablePrompt(context: Context) {
        promptShownCountForTest++
        val prefs = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FREEFORM_PROMPT_MUTED, false)) {
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val previous = activeGuideDialogRef?.get()
        if (previous?.isShowing == true) {
            return
        }

        try {
            val builder = AlertDialog.Builder(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            )
                .setTitle(R.string.freeform_prompt_title)
                .setMessage(R.string.freeform_prompt_message)
                .setPositiveButton(R.string.freeform_prompt_open_dev_options) { dialog, _ ->
                    dialog.dismiss()
                    openDeveloperOptions(context)
                }
                .setNeutralButton(R.string.freeform_prompt_dont_ask) { dialog, _ ->
                    prefs.edit().putBoolean(PREF_FREEFORM_PROMPT_MUTED, true).apply()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }

            val alert = builder.create()
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            alert.window?.setType(windowType)
            alert.show()
            activeGuideDialogRef = WeakReference(alert)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show freeform overlay prompt dialog; falling back to Toast", e)
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openDeveloperOptions(context: Context) {
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(devIntent)
        } catch (_: Exception) {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(settingsIntent) }
            Toast.makeText(
                context,
                context.getString(R.string.freeform_unlock_dev_options_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
